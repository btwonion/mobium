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
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Module 1: block entities that do not change are captured once through vanilla's own submit and prepare code
 * ([Capture]) and then drawn from per-section GPU buffers ([SectionBucket]) instead of being extracted, submitted,
 * posed and tessellated every frame. [CachePolicy] decides per type what is cached and while which states.
 *
 * Per frame: the extract hook returns no render state for cached objects (vanilla then neither submits nor
 * tessellates them) and marks them visible; after the level's prepare stage [prepare] rewrites the buckets that
 * changed or that the camera moved away from, and [draw] draws the visible ones at the end of the level's solid
 * features, where vanilla draws them too.
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

    /** Dev measurements only: skips the cache's draw calls (cached objects vanish) to tell their cost apart. */
    @JvmField
    var skipDraws = false

    private var frame = 1L
    private var irisLoaded = false
    private val entries = Long2ObjectOpenHashMap<CacheEntry>()

    /** Light-change counter per section, bumped by `ClientChunkCache.onLightUpdate`; 0 for untouched sections. */
    private val lightVersions = Long2IntOpenHashMap()
    private val buckets = Long2ObjectOpenHashMap<HashMap<RenderType, SectionBucket>>()
    private val renderTypes = HashMap<RenderType, CachedRenderType>()
    private val dirtyBuckets = ArrayList<SectionBucket>()
    private val visibleBuckets = ArrayList<SectionBucket>()
    private val neighbour = BlockPos.MutableBlockPos()
    private val pending = ArrayList<CacheEntry>()
    private val budget = MemoryBudget(config.cache.memoryBudgetMb * MemoryBudget.BYTES_PER_MB)
    private var captureDispatcher: BlockEntityRenderDispatcher? = null
    private var irisPipeline: Any? = null

    /** Bumped by every client light update; an entry that saw the current value needs no light check. */
    private var lightEpoch = 0

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
            !rule.checksState && known.lightEpoch == lightEpoch && (frame + known.stagger) and REVALIDATE_MASK != 0L
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
        val lightVersion = lightVersion(pos, hasNeighbour)
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
        if (entry != null && (entry.lightVersion != lightVersion || (frame + entry.stagger) and REVALIDATE_MASK == 0L ||
                entry.lightEpoch != lightEpoch)
        ) {
            val light = LightCoordsUtil.getLightCoords(level, pos)
            val neighbourLight = if (hasNeighbour) LightCoordsUtil.getLightCoords(level, neighbour) else 0
            if (entry.light == light && entry.neighbourLight == neighbourLight) {
                entry.lightVersion = lightVersion
                entry.lightEpoch = lightEpoch
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
                    paused.lightVersion = stale.lightVersion
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
            entry.lightVersion = lightVersion
            entry.lightEpoch = lightEpoch
            entry.renderer = renderer
            if (hasNeighbour) entry.neighbourEntity = level.getBlockEntity(neighbour)
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

    private fun lightVersion(pos: BlockPos, hasNeighbour: Boolean): Int {
        if (lightVersions.isEmpty()) return 0
        var version = lightVersions.get(SectionPos.asLong(pos.x shr 4, pos.y shr 4, pos.z shr 4))
        if (hasNeighbour) {
            version += lightVersions.get(SectionPos.asLong(neighbour.x shr 4, neighbour.y shr 4, neighbour.z shr 4))
        }
        return version
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
        lightVersions.addTo(SectionPos.asLong(sectionX, sectionY, sectionZ), 1)
        lightEpoch++
        lightBumps++
    }

    // endregion

    // region capture

    /** Captures the objects that were first seen this frame; their entries become cacheable when this succeeds. */
    private fun capturePending() {
        val dispatcher = captureDispatcher ?: return
        for (i in pending.indices) {
            val entry = pending[i]
            val state = entry.pendingState ?: continue
            entry.pendingState = null
            if (entries.get(entry.blockEntity.blockPos.asLong()) === entry) capture(entry, dispatcher, state)
        }
        pending.clear()
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
        val parts = arrayOfNulls<EntryPart>(meshes.size)
        for (i in meshes.indices) {
            val bucket = bucket(sectionKey, meshes[i].renderType)
            val member = BucketMember(meshes[i], pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
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
        dropParts(entry)
        entries.remove(entry.blockEntity.blockPos.asLong())
        val holder = entry.blockEntity as CachedBlockEntity
        if (holder.`mobium$cacheEntry`() === entry) holder.`mobium$setCacheEntry`(null)
    }

    /** Frees an entry's meshes and takes them out of their buckets. */
    private fun dropParts(entry: CacheEntry) {
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
        val candidates = entries.values.filter { it.bytes > 0L }
        val distances = DoubleArray(candidates.size)
        val bytes = LongArray(candidates.size)
        for (i in candidates.indices) {
            val pos = candidates[i].blockEntity.blockPos
            val dx = pos.x + 0.5 - cameraX
            val dy = pos.y + 0.5 - cameraY
            val dz = pos.z + 0.5 - cameraZ
            distances[i] = dx * dx + dy * dy + dz * dz
            bytes[i] = candidates[i].bytes
        }
        val evicted = budget.selectEvictions(distances, bytes, candidates.size)
        for (index in evicted) {
            val entry = candidates[index]
            dropParts(entry)
            entry.cacheable = false
            entry.evicted = true
        }
        if (evicted.isNotEmpty()) {
            Mobium.LOGGER.debug("Mobium static cache over its memory budget: evicted {} objects", evicted.size)
        }
    }

    private fun removeType(rule: CacheRule) {
        val stale = entries.values.filter { it.rule === rule }
        stale.forEach(::remove)
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
        for (bucket in dirtyBuckets) {
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
        val stale = entries.values.filter {
            frame - it.seenFrame > EVICT_AFTER_FRAMES || it.blockEntity.isRemoved || (resume && it.evicted)
        }
        stale.forEach(::remove)
    }

    /** Drops every cached object and GPU buffer (level change, reload, render distance change, switched off). */
    @JvmStatic
    fun clear() {
        for (entry in entries.values) {
            for (part in entry.parts) part.member.mesh.free()
            (entry.blockEntity as CachedBlockEntity).`mobium$setCacheEntry`(null)
        }
        entries.clear()
        for (entry in pending) entry.pendingState = null
        pending.clear()
        lightVersions.clear()
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
        entries.values.count { it.cacheable && (key == null || it.rule.key == key) }

    /** The vertex sizes of the cached meshes (36 and 28 bytes for vanilla's entity and text formats). */
    fun vertexSizes(): Set<Int> {
        val sizes = HashSet<Int>()
        for (entry in entries.values) for (part in entry.parts) sizes += part.member.mesh.vertexSize
        return sizes
    }

    /** A few entries of one rule, for failure reports of the dev self-check. */
    fun describeEntries(key: String, limit: Int): String = entries.values.filter { it.rule.key == key }.take(limit)
        .joinToString { "${it.blockEntity.blockPos.toShortString()} removed=${it.blockEntity.isRemoved} " +
            "unseen=${frame - it.seenFrame} cacheable=${it.cacheable}" }

    val bucketCount: Int
        get() = buckets.values.sumOf { it.size }

    fun debugLine(): String {
        val types = CachePolicy.all.joinToString(", ") { "${it.key} ${cachedCount(it.key)}" }
        return "Mobium static cache ${if (active) "on" else "off"}: $types; $lastDrawn drawn, $bucketCount buckets, " +
            String.format(
                Locale.ROOT, "%.1f of %d MB", budget.usedBytes / 1048576.0, budget.budgetBytes / MemoryBudget.BYTES_PER_MB
            ) +
            ", ${lastUploadBytes / 1024} KB uploaded; last frame $lastFastHits fast, $lastSlowChecks full checks, " +
            "$lastLightBumps light updates"
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
 * One cached object: the inputs its capture depends on and its meshes in the section buckets. Objects whose capture
 * cannot be drawn keep an entry with [cacheable] false, so that they are not captured again every frame.
 */
class CacheEntry(
    @JvmField val rule: CacheRule,
    @JvmField val blockEntity: BlockEntity,
    @JvmField val blockState: BlockState,
    @JvmField val light: Int,
    @JvmField val neighbourLight: Int
) {
    @JvmField
    var parts: Array<EntryPart> = NO_PARTS

    @JvmField
    var cacheable = false

    /** The renderer vanilla used for the object when the entry was made (renderers change only on reload). */
    @JvmField
    var renderer: BlockEntityRenderer<BlockEntity, BlockEntityRenderState> = NO_RENDERER

    /** The other half of a double chest, when the entry was made. */
    @JvmField
    var neighbourEntity: BlockEntity? = null

    /** The [StaticCache] light epoch the entry's light was last checked at. */
    @JvmField
    var lightEpoch = 0

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

    @JvmField
    var seenFrame = 0L

    /** The section light versions the stored [light] values were last checked against. */
    @JvmField
    var lightVersion = 0

    /** Spreads the periodic light re-check of all objects over the frames. */
    @JvmField
    val stagger = (blockEntity.blockPos.asLong() * -7046029254386353131L ushr 57)

    fun matches(rule: CacheRule, blockEntity: BlockEntity, state: BlockState): Boolean = this.rule === rule &&
        this.blockEntity === blockEntity && blockState === state && rule.sameInputs(this, blockEntity)

    fun markVisible(frame: Long) {
        for (i in parts.indices) {
            val part = parts[i]
            part.member.visibleFrame = frame
            StaticCache.markVisible(part.bucket)
        }
    }
}

class EntryPart(@JvmField val bucket: SectionBucket, @JvmField val member: BucketMember)
