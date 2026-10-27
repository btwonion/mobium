package dev.nyon.mobium.cache

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.renderpearl.api.commands.RenderPass
import dev.nyon.mobium.Mobium
import dev.nyon.mobium.config.config
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.CompatMod
import dev.nyon.mobium.core.Fallback
import dev.nyon.mobium.core.IrisCompat
import dev.nyon.mobium.core.Module
import dev.nyon.mobium.core.ModuleStates
import dev.nyon.mobium.gpu.Gpu
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.entity.EntityRenderDispatcher
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.util.LightCoordsUtil
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Module 1: block entities and entities that do not change are captured once through vanilla's own submit and prepare
 * code ([Capture]) and then drawn from per-section GPU buffers ([SectionBucket]) instead of being submitted, posed and
 * tessellated every frame. [CachePolicy] and [EntityPolicy] decide per type what is cached and while which states.
 *
 * Per frame: the block entity extract hook returns no render state for cached block entities (vanilla then neither
 * submits nor tessellates them); entities are always extracted, and the hook around their renderer's submit skips it
 * for cached ones ([entity]). Both mark the cached object visible; after the level's prepare stage [prepare] rewrites
 * the buckets that changed or that the camera moved away from, and [draw] draws the visible ones at the end of the
 * level's solid features, where vanilla draws them too.
 *
 * Fallback: everything a rule does not accept goes through vanilla unchanged, an object whose capture cannot be drawn
 * stays on vanilla until its inputs change, repeated capture failures switch the type off and an exception switches
 * Module 1 off for the session ([Fallback]). Render thread only.
 */
object StaticCache {
    private const val EVICT_AFTER_FRAMES = 2400L
    private const val SWEEP_INTERVAL_FRAMES = 600L
    private const val REVALIDATE_MASK = 127L

    /** An object whose picture changes this soon after its capture counts as volatile... */
    private const val VOLATILE_FRAMES = 60L

    /** ...and takes the vanilla path for this many frames before it is tried again (a spinning compass). */
    private const val VOLATILE_PAUSE_FRAMES = 1200L

    /** Read by the mixin on `BlockEntityRenderDispatcher.tryExtractRenderState` before anything else. */
    @JvmField
    var active = false

    /**
     * Measurements only (`/mobium gateb`, the dev self-check): skips the cache's draw calls, so the cached objects
     * vanish and the GPU has nothing to draw for them; everything else the cache does per frame still runs.
     */
    @JvmField
    var skipDraws = false

    private var frame = 1L
    private var irisLoaded = false
    private val entries = Long2ObjectOpenHashMap<CacheEntry>()

    /** Entity entries by entity id (the entry itself is kept on the entity, [CachedEntity]). */
    private val entityEntries = Int2ObjectOpenHashMap<EntityEntry>()

    /** The light epoch and each section's last light update (`ClientChunkCache.onLightUpdate`); pruned by the sweep. */
    private val lightStamps = LightStamps()
    private val buckets = Long2ObjectOpenHashMap<HashMap<RenderType, SectionBucket>>()
    private val renderTypes = HashMap<RenderType, CachedRenderType>()
    private val dirtyBuckets = ArrayList<SectionBucket>()
    private val visibleBuckets = ArrayList<SectionBucket>()
    private val neighbour = BlockPos.MutableBlockPos()
    private val pending = ArrayList<CacheEntry>()
    private val pendingEntities = ArrayList<EntityEntry>()
    private val budget = MemoryBudget(config.cache.memoryBudgetMb * MemoryBudget.BYTES_PER_MB)
    private var captureDispatcher: BlockEntityRenderDispatcher? = null
    private var entityDispatcher: EntityRenderDispatcher? = null
    private var irisPipeline: Any? = null

    // Reused by the budget eviction, which runs only while the cache is over its budget.
    private val evictionCandidates = ArrayList<CachedObject>()
    private var evictionDistances = DoubleArray(0)
    private var evictionBytes = LongArray(0)
    private var evictionOrder = IntArray(0)

    /** `Minecraft.isTextFilteringEnabled`, read once per frame (it waits on a future every call). */
    @JvmField
    internal var textFiltering = false
    private var scratch: ByteBuffer? = null

    var lastDrawn = 0
        private set

    private var fastHits = 0
    private var slowChecks = 0
    private var lightBumps = 0
    private var lastFastHits = 0
    private var lastSlowChecks = 0
    private var lastLightBumps = 0

    /** Fast-path hits, full checks and light updates since the start (dev profiling). */
    var totals = LongArray(3)
        private set

    /** Vertex bytes uploaded in the last frame (0 while the camera stands still and nothing changes). */
    var lastUploadBytes = 0L
        private set

    // region availability

    /** Switched by [ModuleStates] (and directly by the benchmark plans and the gametests); off drops everything. */
    fun setActive(value: Boolean) {
        if (!value) clear()
        budget.resize(config.cache.memoryBudgetMb * MemoryBudget.BYTES_PER_MB)
        refreshTypes()
        irisLoaded = Compat.isLoaded(CompatMod.IRIS)
        active = value
    }

    /** Re-reads the per-type switches; a type that was switched off drops its cached objects. */
    fun refreshTypes() {
        for (rule in CachePolicy.all) {
            val allowed = rule.enabled(config.cache.types) && !Fallback.isTypeDisabled(Module.STATIC_CACHE, rule.key)
            if (rule.allowed && !allowed) removeType(rule)
            rule.allowed = allowed
        }
    }

    /** True if at least one type may be cached. */
    fun anyTypeAllowed(): Boolean =
        CachePolicy.all.any { it.enabled(config.cache.types) && !Fallback.isTypeDisabled(Module.STATIC_CACHE, it.key) }

    /** Any exception switches Module 1 off for the session; the caller then takes the vanilla path. */
    private fun fail(error: Throwable) {
        Fallback.moduleFailed(Module.STATIC_CACHE, error)
        if (active) setActive(false)
    }

    // endregion

    // region extract

    /**
     * Replaces `BlockEntityRenderDispatcher.tryExtractRenderState` while [active]. Returns null for objects drawn from
     * the cache and the vanilla result for everything else.
     */
    @JvmStatic
    fun extract(
        dispatcher: BlockEntityRenderDispatcher,
        blockEntity: BlockEntity,
        partialTicks: Float,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
        isGloballyRendered: Boolean,
        frustum: Frustum?,
        cameraPos: Vec3,
        original: Operation<BlockEntityRenderState?>
    ): BlockEntityRenderState? {
        val rule = CachePolicy.ruleFor(blockEntity)
        // The breaking overlay is drawn by vanilla for that frame; Iris's shadow pass extracts again and stays vanilla.
        // Removed block entities can still be handed in for a while (a section's block entity list is only rebuilt
        // with its mesh); vanilla decides what to do with them.
        if (rule == null || !rule.allowed || breakProgress != null || isGloballyRendered || blockEntity.isRemoved ||
            shadowPass()
        ) {
            return vanilla(original, blockEntity, partialTicks, breakProgress, isGloballyRendered, frustum)
        }
        return try {
            extractCached(rule, dispatcher, blockEntity, partialTicks, frustum, cameraPos, original)
        } catch (e: Exception) {
            fail(e)
            vanilla(original, blockEntity, partialTicks, breakProgress, isGloballyRendered, frustum)
        }
    }

    private fun shadowPass(): Boolean = irisLoaded && IrisCompat.isRenderingShadowPass()

    private fun extractCached(
        rule: CacheRule,
        dispatcher: BlockEntityRenderDispatcher,
        blockEntity: BlockEntity,
        partialTicks: Float,
        frustum: Frustum?,
        cameraPos: Vec3,
        original: Operation<BlockEntityRenderState?>
    ): BlockEntityRenderState? {
        // Fast path for a cached object whose block state is unchanged: everything that only depends on the state
        // was checked when the entry was made, so only culling, motion, inputs and light remain.
        val known = (blockEntity as CachedBlockEntity).`mobium$cacheEntry`() as? CacheEntry
        if (known != null && known.cacheable && known.rule === rule && known.blockState === blockEntity.blockState &&
            !rule.checksState && lightUnchanged(known) && (frame + known.stagger) and REVALIDATE_MASK != 0L
        ) {
            val renderer = known.renderer
            if (!renderer.shouldRender(blockEntity, cameraPos)) return null
            /*? if neoforge {*/
            /*if (frustum != null && !frustum.isVisible(renderer.getRenderBoundingBox(blockEntity))) return null
            *//*?}*/
            if (rule.isStaticCached(known, blockEntity, partialTicks) && rule.sameInputs(known, blockEntity)) {
                known.seenFrame = frame
                known.markVisible(frame)
                fastHits++
                return null
            }
        }
        slowChecks++

        val level = blockEntity.level
        val renderer = dispatcher.getRenderer<BlockEntity, BlockEntityRenderState>(blockEntity)
        if (level == null || renderer == null || !blockEntity.type.isValid(blockEntity.blockState)) {
            return vanilla(original, blockEntity, partialTicks, null, false, frustum)
        }
        // Same culling as the extraction it replaces.
        if (renderer.shouldRenderOffScreen() || !renderer.shouldRender(blockEntity, cameraPos)) return null
        /*? if neoforge {*/
        /*if (frustum != null && !frustum.isVisible(renderer.getRenderBoundingBox(blockEntity))) return null
        *//*?}*/

        val state = blockEntity.blockState
        if (!rule.isStatic(blockEntity, level, state, partialTicks)) {
            return vanilla(original, blockEntity, partialTicks, null, false, frustum)
        }

        val pos = blockEntity.blockPos
        val hasNeighbour = rule.neighbour(pos, state, neighbour)
        var entry = entries.get(pos.asLong())
        if (entry != null && (!entry.matches(rule, blockEntity, state) || entry.volatileUntil in 1..<frame)) {
            remove(entry)
            entry = null
        }
        if (entry != null && hasNeighbour && entry.neighbourEntity?.isRemoved != false) {
            entry.neighbourEntity = level.getBlockEntity(neighbour)
        }
        // Light is read again after a light update touched the object's section (or its other half's), and every
        // REVALIDATE_MASK + 1 frames anyway: light data sent by the server does not always announce itself.
        if (entry != null && (!lightUnchanged(entry) || (frame + entry.stagger) and REVALIDATE_MASK == 0L)) {
            val light = LightCoordsUtil.getLightCoords(level, pos)
            val neighbourLight = if (hasNeighbour) LightCoordsUtil.getLightCoords(level, neighbour) else 0
            if (entry.light == light && entry.neighbourLight == neighbourLight) {
                entry.lightEpoch = lightStamps.epoch
            } else {
                remove(entry)
                entry = null
            }
        }

        // Rules whose picture can only be told from vanilla's state (items) extract every frame and compare.
        var extracted: BlockEntityRenderState? = null
        if (entry != null && entry.cacheable && rule.checksState) {
            extracted = vanilla(original, blockEntity, partialTicks, null, false, frustum) ?: return null
            if (!rule.sameState(entry, extracted)) {
                val stale = entry
                remove(stale)
                entry = null
                if (frame - stale.capturedFrame < VOLATILE_FRAMES) {
                    val paused = CacheEntry(rule, blockEntity, state, stale.light, stale.neighbourLight)
                    paused.lightEpoch = stale.lightEpoch
                    paused.volatileUntil = frame + VOLATILE_PAUSE_FRAMES
                    entries.put(pos.asLong(), paused)
                    (blockEntity as CachedBlockEntity).`mobium$setCacheEntry`(paused)
                    return extracted
                }
            }
        }

        if (entry == null) {
            val vanilla = extracted ?: vanilla(original, blockEntity, partialTicks, null, false, frustum) ?: return null
            val light = LightCoordsUtil.getLightCoords(level, pos)
            val neighbourLight = if (hasNeighbour) LightCoordsUtil.getLightCoords(level, neighbour) else 0
            entry = CacheEntry(rule, blockEntity, state, light, neighbourLight)
            entry.lightEpoch = lightStamps.epoch
            entry.renderer = renderer
            entry.hasNeighbour = hasNeighbour
            if (hasNeighbour) {
                entry.neighbourEntity = level.getBlockEntity(neighbour)
                entry.neighbourSection = SectionPos.blockToSection(neighbour.asLong())
            }
            rule.storeInputs(entry, blockEntity)
            entries.put(pos.asLong(), entry)
            (blockEntity as CachedBlockEntity).`mobium$setCacheEntry`(entry)
            // Vanilla draws the object this frame; it is captured in prepare, inside the level render like vanilla's
            // own submit (Iris only extends vertex formats and records entity ids there), and cached from the next.
            if (rule.canCache(entry, blockEntity) && rule.storeState(entry, vanilla)) {
                entry.pendingState = vanilla
                pending += entry
                captureDispatcher = dispatcher
            }
            return vanilla
        } else if (!entry.cacheable) {
            return vanilla(original, blockEntity, partialTicks, null, false, frustum)
        }

        entry.seenFrame = frame
        entry.markVisible(frame)
        return null
    }

    /**
     * Called at the start of `LevelExtractor.extract`, before any object of the frame is extracted. Captures hold
     * Iris's extended vertex format and entity ids while a shader pack is on, so a new Iris pipeline (reload, another
     * pack, pack options, packs switched on or off) starts the cache over.
     */
    @JvmStatic
    fun beginFrame() {
        if (!active) return
        textFiltering = Minecraft.getInstance().isTextFilteringEnabled
        if (!irisLoaded) return
        val pipeline = IrisCompat.pipeline()
        if (pipeline !== irisPipeline) {
            if (irisPipeline != null) clear()
            irisPipeline = pipeline
        }
    }

    /** Calls the wrapped vanilla method; NeoForge's overload also takes the culling frustum. */
    @Suppress("UNUSED_PARAMETER")
    private fun vanilla(
        original: Operation<BlockEntityRenderState?>,
        blockEntity: BlockEntity,
        partialTicks: Float,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
        isGloballyRendered: Boolean,
        frustum: Frustum?
    ): BlockEntityRenderState? {
        /*? if fabric {*/
        return original.call(blockEntity, partialTicks, breakProgress, isGloballyRendered)
        /*?} else {*/
        /*return original.call(blockEntity, partialTicks, breakProgress, isGloballyRendered, frustum)
        *//*?}*/
    }

    /**
     * True if no light update reached the entry's section (or its other half's) since the entry last read its light;
     * the entry then counts as checked at the current epoch. Light updates in other sections cost one or two lookups.
     */
    private fun lightUnchanged(entry: CacheEntry): Boolean {
        val epoch = lightStamps.epoch
        if (entry.lightEpoch == epoch) return true
        if (lightStamps.changedSince(entry.lightSection, entry.lightEpoch) ||
            entry.hasNeighbour && lightStamps.changedSince(entry.neighbourSection, entry.lightEpoch)
        ) {
            return false
        }
        entry.lightEpoch = epoch
        return true
    }

    /** Called from `BlockEntity.setRemoved`: drops the object's cached picture (client block entities only). */
    @JvmStatic
    fun onRemoved(blockEntity: BlockEntity) {
        if (!RenderSystem.isOnRenderThread() || blockEntity.level?.isClientSide != true) return
        val entry = (blockEntity as CachedBlockEntity).`mobium$cacheEntry`() as? CacheEntry ?: return
        if (entry.blockEntity === blockEntity) remove(entry)
    }

    /** Called from `ClientChunkCache.onLightUpdate`: cached objects in the section re-read their light. */
    @JvmStatic
    fun onLightUpdate(sectionX: Int, sectionY: Int, sectionZ: Int) {
        if (!active) return
        lightStamps.update(SectionPos.asLong(sectionX, sectionY, sectionZ))
        lightBumps++
    }

    // endregion

    // region entities

    /**
     * Called after `EntityRenderDispatcher.extractEntity`: true if the render state should keep a reference to its
     * entity for [entity], which only types the cache may take need. Iris's shadow pass keeps its render states until
     * its next pass and the cache leaves that pass to vanilla, so its states get none.
     */
    @JvmStatic
    fun linksEntity(entity: Entity): Boolean {
        if (!active || shadowPass()) return false
        val rule = EntityPolicy.ruleFor(entity)
        return rule != null && rule.allowed
    }

    /**
     * Called from the wrapper around the renderer's submit inside `EntityRenderDispatcher.submit` while [active]. True
     * when the entity is drawn from the cache this frame (the renderer's submit is then skipped); false lets vanilla
     * submit it, and an entity seen for the first time in a cacheable state is captured in [prepare].
     */
    @JvmStatic
    fun entity(dispatcher: EntityRenderDispatcher, state: EntityRenderState): Boolean {
        // Captures submit through the dispatcher themselves; GUI and picture-in-picture entities and Iris's shadow
        // pass stay vanilla.
        if (Capture.capturingEntity || !RenderSystem.isRenderingLevel || shadowPass()) return false
        val entity = (state as EntityStateSource).`mobium$entity`() ?: return false
        val rule = EntityPolicy.ruleFor(entity)
        if (rule == null || !rule.allowed || entity.isRemoved) return false
        return try {
            entityCached(rule, dispatcher, entity, state)
        } catch (e: Exception) {
            fail(e)
            false
        }
    }

    private fun entityCached(
        rule: EntityRule,
        dispatcher: EntityRenderDispatcher,
        entity: Entity,
        state: EntityRenderState
    ): Boolean {
        // Not still this frame (wobbling, interpolating, a name tag, glowing, ...): vanilla, the entry is kept.
        if (!EntityPolicy.isPlain(state) || !rule.isStatic(state)) return false
        var entry = (entity as CachedEntity).`mobium$cacheEntry`() as? EntityEntry
        if (entry != null) {
            entry.seenFrame = frame
            if (entry.volatileUntil != 0L) {
                if (entry.volatileUntil >= frame) return false
                removeEntity(entry)
                entry = null
            } else if (entry.evicted) {
                return false
            } else if (entry.matches(state)) {
                if (!entry.cacheable) {
                    slowChecks++
                    return false
                }
                entry.markVisible(frame)
                fastHits++
                return true
            } else {
                // Changed: captured again from this frame's state, unless it changed right after its capture.
                val stale = entry
                removeEntity(stale)
                entry = null
                if (stale.cacheable && frame - stale.capturedFrame < VOLATILE_FRAMES) {
                    pauseEntity(rule, entity, state)
                    return false
                }
            }
        }
        slowChecks++

        entry = EntityEntry(rule, entity, state.x, state.y, state.z, state.lightCoords)
        entry.seenFrame = frame
        entityEntries.put(entity.id, entry)?.let { previous -> if (previous !== entry) dropEntityEntry(previous) }
        entity.`mobium$setCacheEntry`(entry)
        if (rule.store(entry, state)) {
            // Vanilla draws the entity this frame; it is captured in prepare and drawn from the cache from the next.
            entry.pendingState = state
            pendingEntities += entry
            entityDispatcher = dispatcher
        } else {
            // Something the cache cannot take (a special item renderer): vanilla for a while, then tried again.
            entry.volatileUntil = frame + VOLATILE_PAUSE_FRAMES
        }
        return false
    }

    /** An entity whose picture changed right after its capture takes the vanilla path for a while (a compass). */
    private fun pauseEntity(rule: EntityRule, entity: Entity, state: EntityRenderState) {
        val paused = EntityEntry(rule, entity, state.x, state.y, state.z, state.lightCoords)
        paused.volatileUntil = frame + VOLATILE_PAUSE_FRAMES
        paused.seenFrame = frame
        entityEntries.put(entity.id, paused)
        (entity as CachedEntity).`mobium$setCacheEntry`(paused)
    }

    private fun removeEntity(entry: EntityEntry) {
        dropParts(entry)
        entry.pendingState = null
        if (entityEntries.get(entry.entity.id) === entry) entityEntries.remove(entry.entity.id)
        val holder = entry.entity as CachedEntity
        if (holder.`mobium$cacheEntry`() === entry) holder.`mobium$setCacheEntry`(null)
    }

    /** An entry replaced in the map by a new entity with the same id (the old one left tracking range). */
    private fun dropEntityEntry(entry: EntityEntry) {
        dropParts(entry)
        entry.pendingState = null
        val holder = entry.entity as CachedEntity
        if (holder.`mobium$cacheEntry`() === entry) holder.`mobium$setCacheEntry`(null)
    }

    // endregion

    // region capture

    /** Captures the objects that were first seen this frame; their entries become cacheable when this succeeds. */
    private fun capturePending() {
        val dispatcher = captureDispatcher
        if (dispatcher != null) {
            for (i in pending.indices) {
                val entry = pending[i]
                val state = entry.pendingState ?: continue
                entry.pendingState = null
                if (entries.get(entry.blockEntity.blockPos.asLong()) === entry) capture(entry, dispatcher, state)
            }
        }
        pending.clear()
        val entityDispatcher = entityDispatcher
        if (entityDispatcher != null) {
            for (i in pendingEntities.indices) {
                val entry = pendingEntities[i]
                val state = entry.pendingState ?: continue
                entry.pendingState = null
                val current = (entry.entity as CachedEntity).`mobium$cacheEntry`()
                if (current === entry) captureEntity(entry, entityDispatcher, state)
            }
        }
        pendingEntities.clear()
    }

    private fun captureEntity(entry: EntityEntry, dispatcher: EntityRenderDispatcher, state: EntityRenderState) {
        val rule = entry.rule
        val meshes = try {
            Capture.entity(dispatcher, state)
        } catch (e: Exception) {
            Mobium.LOGGER.warn("Could not capture the {} {}; it stays on the vanilla path", rule.key, entry.entity, e)
            Fallback.typeFailed(Module.STATIC_CACHE, rule.key, e)
            if (Fallback.isTypeDisabled(Module.STATIC_CACHE, rule.key)) ModuleStates.refresh("${rule.key} failed")
            null
        }
        if (meshes.isNullOrEmpty()) {
            entry.rejection = "${Capture.lastRejection}, ${state.javaClass.simpleName}" +
                if (state is DisplayEntityRenderState) " sub state ${state.hasSubState()}" else ""
            return
        }
        val sectionKey = SectionPos.asLong(
            SectionPos.blockToSectionCoord(Mth.floor(entry.x)),
            SectionPos.blockToSectionCoord(Mth.floor(entry.y)),
            SectionPos.blockToSectionCoord(Mth.floor(entry.z))
        )
        addParts(entry, sectionKey, meshes, entry.x, entry.y, entry.z)
    }

    private fun capture(entry: CacheEntry, dispatcher: BlockEntityRenderDispatcher, vanilla: BlockEntityRenderState) {
        val rule = entry.rule
        val pos = entry.blockEntity.blockPos
        val meshes = try {
            Capture.blockEntity(dispatcher, vanilla)
        } catch (e: Exception) {
            // This object stays on vanilla; repeated failures switch its type off for the session.
            Mobium.LOGGER.warn("Could not capture the {} at {}; it stays on the vanilla path", rule.key, pos, e)
            Fallback.typeFailed(Module.STATIC_CACHE, rule.key, e)
            if (Fallback.isTypeDisabled(Module.STATIC_CACHE, rule.key)) ModuleStates.refresh("${rule.key} failed")
            null
        }
        if (meshes.isNullOrEmpty()) return

        val sectionKey = SectionPos.asLong(pos.x shr 4, pos.y shr 4, pos.z shr 4)
        addParts(entry, sectionKey, meshes, pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
    }

    /** Puts a captured object's meshes into its section's buckets; its local space starts at x, y, z. */
    private fun addParts(
        entry: CachedObject,
        sectionKey: Long,
        meshes: List<CapturedMesh>,
        x: Double,
        y: Double,
        z: Double
    ) {
        val parts = arrayOfNulls<EntryPart>(meshes.size)
        for (i in meshes.indices) {
            val bucket = bucket(sectionKey, meshes[i].renderType)
            val member = BucketMember(meshes[i], x, y, z)
            bucket.add(member)
            markDirty(bucket)
            parts[i] = EntryPart(bucket, member)
        }
        @Suppress("UNCHECKED_CAST")
        entry.parts = parts as Array<EntryPart>
        entry.cacheable = true
        entry.capturedFrame = frame
        var bytes = 0L
        for (mesh in meshes) bytes += mesh.bytes
        entry.bytes = bytes
        budget.add(bytes)
    }

    // endregion

    // region buckets

    private fun bucket(sectionKey: Long, renderType: RenderType): SectionBucket {
        val byType = buckets.get(sectionKey) ?: HashMap<RenderType, SectionBucket>().also { buckets.put(sectionKey, it) }
        return byType.getOrPut(renderType) {
            SectionBucket(sectionKey, renderTypes.getOrPut(renderType) { CachedRenderType(renderType) })
        }
    }

    private fun markDirty(bucket: SectionBucket) {
        if (!bucket.dirty) {
            bucket.dirty = true
            dirtyBuckets += bucket
        }
    }

    private fun remove(entry: CacheEntry) {
        detach(entry)
        entries.remove(entry.blockEntity.blockPos.asLong())
    }

    /** Everything [remove] does except taking the entry out of [entries] (the sweeps remove it with their iterator). */
    private fun detach(entry: CacheEntry) {
        dropParts(entry)
        val holder = entry.blockEntity as CachedBlockEntity
        if (holder.`mobium$cacheEntry`() === entry) holder.`mobium$setCacheEntry`(null)
    }

    /** Frees an entry's meshes and takes them out of their buckets. */
    private fun dropParts(entry: CachedObject) {
        for (part in entry.parts) {
            part.bucket.remove(part.member)
            part.member.mesh.free()
            markDirty(part.bucket)
        }
        entry.parts = NO_PARTS
        budget.remove(entry.bytes)
        entry.bytes = 0L
    }

    /**
     * Above the memory budget: evicts the farthest cached objects ([MemoryBudget]). They keep their entry, marked
     * evicted, and take the vanilla path until the sweep lets them be captured again.
     */
    private fun enforceBudget(cameraX: Double, cameraY: Double, cameraZ: Double) {
        val candidates = evictionCandidates
        for (entry in entries.values) if (entry.bytes > 0L) candidates += entry
        for (entry in entityEntries.values) if (entry.bytes > 0L) candidates += entry
        val count = candidates.size
        if (evictionOrder.size < count) {
            val capacity = maxOf(count, evictionOrder.size * 2)
            evictionDistances = DoubleArray(capacity)
            evictionBytes = LongArray(capacity)
            evictionOrder = IntArray(capacity)
        }
        val distances = evictionDistances
        val bytes = evictionBytes
        for (i in 0 until count) {
            val candidate = candidates[i]
            val dx = candidate.centerX() - cameraX
            val dy = candidate.centerY() - cameraY
            val dz = candidate.centerZ() - cameraZ
            distances[i] = dx * dx + dy * dy + dz * dz
            bytes[i] = candidate.bytes
        }
        val evicted = budget.selectEvictions(distances, bytes, count, evictionOrder)
        for (i in 0 until evicted) {
            val entry = candidates[evictionOrder[i]]
            dropParts(entry)
            entry.cacheable = false
            entry.evicted = true
        }
        candidates.clear()
        if (evicted > 0) Mobium.LOGGER.debug("Mobium static cache over its memory budget: evicted {} objects", evicted)
    }

    private fun removeType(rule: TypeRule) {
        val blockEntities = entries.values.iterator()
        while (blockEntities.hasNext()) {
            val entry = blockEntities.next()
            if (entry.rule === rule) {
                detach(entry)
                blockEntities.remove()
            }
        }
        val entities = entityEntries.values.iterator()
        while (entities.hasNext()) {
            val entry = entities.next()
            if (entry.rule === rule) {
                dropEntityEntry(entry)
                entities.remove()
            }
        }
    }

    internal fun markVisible(bucket: SectionBucket) {
        if (bucket.visibleFrame != frame) {
            bucket.visibleFrame = frame
            visibleBuckets += bucket
        }
    }

    // endregion

    // region frame

    /**
     * Runs right after the level's prepare stage, with the level's model-view matrix on the stack: drops emptied
     * buckets, uploads the visible buckets whose members changed or whose camera-relative positions are stale, and
     * prepares each render type once.
     */
    @JvmStatic
    fun prepare(cameraX: Double, cameraY: Double, cameraZ: Double) {
        if (!active) return
        try {
            prepareBuckets(cameraX, cameraY, cameraZ)
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun prepareBuckets(cameraX: Double, cameraY: Double, cameraZ: Double) {
        capturePending()
        if (budget.isOver) enforceBudget(cameraX, cameraY, cameraZ)
        for (i in dirtyBuckets.indices) {
            val bucket = dirtyBuckets[i]
            bucket.dirty = false
            if (bucket.isEmpty) {
                bucket.close()
                buckets.get(bucket.sectionKey)?.let { byType ->
                    byType.remove(bucket.renderType)
                    if (byType.isEmpty()) buckets.remove(bucket.sectionKey)
                }
            }
        }
        dirtyBuckets.clear()

        var uploaded = 0L
        var maxVertices = 0
        for (i in visibleBuckets.indices) {
            val bucket = visibleBuckets[i]
            if (bucket.isEmpty) continue
            if (bucket.needsUpload(cameraX, cameraY, cameraZ)) {
                bucket.upload(cameraX, cameraY, cameraZ, scratch(bucket.byteCount))
                uploaded += bucket.byteCount
            }
            val type = bucket.type
            if (type.preparedFrame != frame) {
                type.prepared = type.renderType.prepare()
                type.preparedFrame = frame
            }
            if (bucket.vertexCount > maxVertices) maxVertices = bucket.vertexCount
        }
        lastUploadBytes = uploaded
        Gpu.backend.requestQuadIndices(maxVertices)

        if (frame % SWEEP_INTERVAL_FRAMES == 0L) evictUnseen()
    }

    private fun scratch(bytes: Int): ByteBuffer {
        val current = scratch
        if (current != null && current.capacity() >= bytes) return current
        val grown = MemoryUtil.memRealloc(current, maxOf(bytes, (current?.capacity() ?: 0) * 2))
        scratch = grown
        return grown
    }

    /** Draws the visible cached objects; called at the end of the level's solid features. */
    @JvmStatic
    fun draw(renderPass: RenderPass) {
        var drawn = 0
        if (active) {
            try {
                for (i in visibleBuckets.indices) {
                    if (skipDraws) break
                    val bucket = visibleBuckets[i]
                    val prepared = bucket.type.prepared ?: continue
                    if (bucket.type.preparedFrame == frame) drawn += bucket.draw(renderPass, prepared, frame)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
        visibleBuckets.clear()
        lastDrawn = drawn
        lastFastHits = fastHits
        lastSlowChecks = slowChecks
        lastLightBumps = lightBumps
        totals[0] += fastHits.toLong()
        totals[1] += slowChecks.toLong()
        totals[2] += lightBumps.toLong()
        fastHits = 0
        slowChecks = 0
        lightBumps = 0
        frame++
    }

    private fun evictUnseen() {
        val resume = !budget.isTight
        var oldestLightCheck = lightStamps.epoch
        val blockEntities = entries.values.iterator()
        while (blockEntities.hasNext()) {
            val entry = blockEntities.next()
            if (frame - entry.seenFrame > EVICT_AFTER_FRAMES || entry.blockEntity.isRemoved || (resume && entry.evicted)) {
                detach(entry)
                blockEntities.remove()
            } else if (entry.lightEpoch < oldestLightCheck) {
                oldestLightCheck = entry.lightEpoch
            }
        }
        // Light updates every remaining entry has seen are not needed any more (entries that are not seen for a while
        // are evicted above, so this cannot be held back for long).
        lightStamps.prune(oldestLightCheck)
        // Removed entities are no longer extracted, so their entries are only found here.
        val entities = entityEntries.values.iterator()
        while (entities.hasNext()) {
            val entry = entities.next()
            if (frame - entry.seenFrame > EVICT_AFTER_FRAMES || entry.entity.isRemoved || (resume && entry.evicted)) {
                dropEntityEntry(entry)
                entities.remove()
            }
        }
    }

    /** Drops every cached object and GPU buffer (level change, reload, render distance change, switched off). */
    @JvmStatic
    fun clear() {
        for (entry in entries.values) {
            for (part in entry.parts) part.member.mesh.free()
            (entry.blockEntity as CachedBlockEntity).`mobium$setCacheEntry`(null)
        }
        entries.clear()
        for (entry in entityEntries.values) {
            for (part in entry.parts) part.member.mesh.free()
            entry.pendingState = null
            val holder = entry.entity as CachedEntity
            if (holder.`mobium$cacheEntry`() === entry) holder.`mobium$setCacheEntry`(null)
        }
        entityEntries.clear()
        for (entry in pending) entry.pendingState = null
        pending.clear()
        pendingEntities.clear()
        lightStamps.clear()
        for (byType in buckets.values) for (bucket in byType.values) bucket.close()
        buckets.clear()
        renderTypes.clear()
        dirtyBuckets.clear()
        visibleBuckets.clear()
        scratch?.let(MemoryUtil::memFree)
        scratch = null
        budget.reset()
    }

    // endregion

    // region debug

    /** Cached objects per rule key (debug screen, tests). */
    fun cachedCount(key: String? = null): Int =
        entries.values.count { it.cacheable && (key == null || it.rule.key == key) } +
            entityEntries.values.count { it.cacheable && (key == null || it.rule.key == key) }

    /** The vertex sizes of the cached meshes (36 and 28 bytes for vanilla's entity and text formats). */
    fun vertexSizes(): Set<Int> {
        val sizes = HashSet<Int>()
        for (entry in entries.values) for (part in entry.parts) sizes += part.member.mesh.vertexSize
        for (entry in entityEntries.values) for (part in entry.parts) sizes += part.member.mesh.vertexSize
        return sizes
    }

    /** A few entries of one rule, for failure reports of the dev self-check. */
    fun describeEntries(key: String, limit: Int): String {
        val blockEntities = entries.values.filter { it.rule.key == key }.take(limit).map {
            "${it.blockEntity.blockPos.toShortString()} removed=${it.blockEntity.isRemoved} " +
                "unseen=${frame - it.seenFrame} cacheable=${it.cacheable}"
        }
        val entities = entityEntries.values.filter { it.rule.key == key }.take(limit).map {
            "${it.entity.type.toShortString()}@${it.entity.blockPosition().toShortString()} removed=${it.entity.isRemoved} " +
                "unseen=${frame - it.seenFrame} cacheable=${it.cacheable} paused=${it.volatileUntil != 0L}" +
                (it.rejection?.let { reason -> " ($reason)" } ?: "")
        }
        return (blockEntities + entities).joinToString()
    }

    val bucketCount: Int
        get() = buckets.values.sumOf { it.size }

    /** Sections whose last light update some entry has not seen yet (pruned by the sweep). */
    val lightSectionsKept: Int
        get() = lightStamps.size

    /** What the cache holds, draws and checked in the last frame, on one line (logs and check details). */
    fun debugLine(): String = debugLines().joinToString("; ")

    /** [debugLine] as three short lines for F3 `mobium:modules`. */
    fun debugLines(): List<String> {
        val types = CachePolicy.all.joinToString(", ") { "${it.key} ${cachedCount(it.key)}" }
        return listOf(
            "Mobium static cache ${if (active) "on" else "off"}: $types",
            "Mobium static cache: $lastDrawn drawn, $bucketCount buckets, " + String.format(
                Locale.ROOT, "%.1f of %d MB", budget.usedBytes / 1048576.0, budget.budgetBytes / MemoryBudget.BYTES_PER_MB
            ) + ", ${lastUploadBytes / 1024} KB uploaded",
            "Mobium static cache last frame: $lastFastHits fast, $lastSlowChecks full checks, $lastLightBumps light " +
                "updates; $lightSectionsKept sections with light updates kept"
        )
    }

    // endregion
}

private val NO_PARTS = emptyArray<EntryPart>()

/** Placeholder until an entry gets its renderer; never asked anything. */
private val NO_RENDERER = object : BlockEntityRenderer<BlockEntity, BlockEntityRenderState> {
    override fun createRenderState() = BlockEntityRenderState()

    override fun submit(
        state: BlockEntityRenderState,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        camera: CameraRenderState
    ) = Unit
}

/**
 * What block entity and entity entries share: the meshes in the section buckets and the state of the capture.
 * Objects whose capture cannot be drawn keep an entry with [cacheable] false, so that they are not captured again
 * every frame.
 */
abstract class CachedObject {
    @JvmField
    var parts: Array<EntryPart> = NO_PARTS

    @JvmField
    var cacheable = false

    /** Bytes of cached vertex data, counted by the memory budget; 0 while nothing is cached. */
    @JvmField
    var bytes = 0L

    /** The frame the object was captured in. */
    @JvmField
    var capturedFrame = 0L

    /** Non-zero while the object's picture changed right after its capture: vanilla until this frame. */
    @JvmField
    var volatileUntil = 0L

    /** Dropped to stay within the memory budget; vanilla draws the object until the sweep lets it in again. */
    @JvmField
    var evicted = false

    @JvmField
    var seenFrame = 0L

    /** The object's centre, for the memory budget's distances. */
    abstract fun centerX(): Double

    abstract fun centerY(): Double

    abstract fun centerZ(): Double

    fun markVisible(frame: Long) {
        for (i in parts.indices) {
            val part = parts[i]
            part.member.visibleFrame = frame
            StaticCache.markVisible(part.bucket)
        }
    }
}

/** One cached block entity: the inputs its capture depends on and its meshes in the section buckets. */
class CacheEntry(
    @JvmField val rule: CacheRule,
    @JvmField val blockEntity: BlockEntity,
    @JvmField val blockState: BlockState,
    @JvmField val light: Int,
    @JvmField val neighbourLight: Int
) : CachedObject() {
    /** The renderer vanilla used for the object when the entry was made (renderers change only on reload). */
    @JvmField
    var renderer: BlockEntityRenderer<BlockEntity, BlockEntityRenderState> = NO_RENDERER

    /** True for half of a double chest: the other half's lid and light belong to the picture too. */
    @JvmField
    var hasNeighbour = false

    /** The other half of a double chest, when the entry was made (null if its chunk was not loaded yet). */
    @JvmField
    var neighbourEntity: BlockEntity? = null

    /** The [LightStamps] epoch the entry's light was last checked at. */
    @JvmField
    var lightEpoch = 0L

    /** The object's chunk section, whose light updates make the entry check its light again. */
    @JvmField
    val lightSection = SectionPos.blockToSection(blockEntity.blockPos.asLong())

    /** The other half's chunk section while [hasNeighbour]. */
    @JvmField
    var neighbourSection = 0L

    /** What vanilla extracted in the frame this object was first seen; captured in [StaticCache.prepare]. */
    @JvmField
    var pendingState: BlockEntityRenderState? = null

    /** Rule-specific inputs (for signs: both sides' text and the text flags). */
    @JvmField
    var inputA: Any? = null

    @JvmField
    var inputB: Any? = null

    @JvmField
    var inputFlags = 0

    /** Spreads the periodic light re-check of all objects over the frames. */
    @JvmField
    val stagger = (blockEntity.blockPos.asLong() * -7046029254386353131L ushr 57)

    fun matches(rule: CacheRule, blockEntity: BlockEntity, state: BlockState): Boolean = this.rule === rule &&
        this.blockEntity === blockEntity && blockState === state && rule.sameInputs(this, blockEntity)

    override fun centerX() = blockEntity.blockPos.x + 0.5

    override fun centerY() = blockEntity.blockPos.y + 0.5

    override fun centerZ() = blockEntity.blockPos.z + 0.5
}

/**
 * One cached entity: where and in which light it was captured, the rule's inputs, and its meshes, in entity-local space
 * starting at the render state's [x], [y], [z].
 */
class EntityEntry(
    @JvmField val rule: EntityRule,
    @JvmField val entity: Entity,
    @JvmField val x: Double,
    @JvmField val y: Double,
    @JvmField val z: Double,
    @JvmField val light: Int
) : CachedObject() {
    /** What the rule keeps of the render state ([EntityRule.store]). */
    @JvmField
    var inputs: Any? = null

    /** The state vanilla extracted in the frame this entity was first seen; captured in [StaticCache.prepare]. */
    @JvmField
    var pendingState: EntityRenderState? = null

    /** Why the capture could not be cached (debug output). */
    @JvmField
    var rejection: String? = null

    /** True when [state] draws what this entry holds: same position (exactly), light and rule inputs. */
    fun matches(state: EntityRenderState): Boolean =
        state.x == x && state.y == y && state.z == z && state.lightCoords == light && rule.same(this, state)

    override fun centerX() = x

    override fun centerY() = y

    override fun centerZ() = z
}

class EntryPart(@JvmField val bucket: SectionBucket, @JvmField val member: BucketMember)
