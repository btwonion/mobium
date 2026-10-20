package dev.nyon.mobium.cache

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import dev.nyon.mobium.Mobium
import dev.nyon.mobium.config.config
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.CompatMod
import dev.nyon.mobium.core.Fallback
import dev.nyon.mobium.core.IrisCompat
import dev.nyon.mobium.core.Module
import dev.nyon.mobium.core.ModuleStates
import dev.nyon.mobium.gpu.Gpu
import dev.nyon.mobium.mixins.RenderTypeFeatureRendererInvoker
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeStorage
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.feature.FeatureRendererType
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase
import net.minecraft.client.renderer.feature.submit.SubmitNode
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.phys.Vec3

/**
 * M3 prototype of Module 1: closed chests are captured once through vanilla's own submit and prepare code and then
 * drawn from per-section GPU buffers ([SectionBucket]) instead of being extracted, submitted, posed and tessellated
 * every frame. OpenGL only, no shader packs, off unless `cache.prototype` is set; [ModuleStates] switches it with
 * Module 1 (settings, A/B key, failures).
 *
 * Fallback: everything that is not a closed, unbroken chest with a known capture goes through vanilla unchanged, a
 * chest whose capture produced anything but solid quads stays on vanilla for good, and an exception switches Module 1
 * off for the session ([Fallback]).
 */
object ChestPrototype {
    private const val EVICT_AFTER_FRAMES = 2400L
    private const val SWEEP_INTERVAL_FRAMES = 600L
    private const val REVALIDATE_MASK = 127L
    private const val TYPE = "chests"

    /** Read by the mixin on `BlockEntityRenderDispatcher.tryExtractRenderState` before anything else. */
    @JvmField
    var active = false

    private var frame = 1L
    private val entries = Long2ObjectOpenHashMap<ChestEntry>()

    /** Light-change counter per section, bumped by `ClientChunkCache.onLightUpdate`; 0 for untouched sections. */
    private val lightVersions = Long2IntOpenHashMap()
    private val buckets = Long2ObjectOpenHashMap<HashMap<RenderType, SectionBucket>>()
    private val dirtyBuckets = ArrayList<SectionBucket>()
    private val visibleBuckets = ArrayList<SectionBucket>()
    private val neighbour = BlockPos.MutableBlockPos()

    private val storage = SubmitNodeStorage()
    private val modelRenderer = ModelFeatureRenderer()
    private val collected = ArrayList<ModelFeatureRenderer.Submit<*>>()
    private var collectedUnsupported = false

    var lastDrawn = 0
        private set

    val cachedCount: Int
        get() = entries.values.count { it.cacheable }

    // region availability

    /** Switched by [ModuleStates] (and directly by `/mobium gatea` and the gametests); off drops every cached chest. */
    fun setActive(value: Boolean) {
        if (!value) clear()
        active = value
    }

    /** The per-type switch for chests, and whether chests failed too often this session. */
    fun chestsAllowed(): Boolean = config.cache.types.chests && !Fallback.isTypeDisabled(Module.STATIC_CACHE, TYPE)

    private fun shaderPackActive() = Compat.isLoaded(CompatMod.IRIS) && IrisCompat.isShaderPackInUse()

    /** Any exception switches Module 1 off for the session; the caller then takes the vanilla path. */
    private fun fail(error: Throwable) {
        Fallback.moduleFailed(Module.STATIC_CACHE, error)
        if (active) setActive(false)
    }

    // endregion

    // region extract

    /**
     * Replaces `BlockEntityRenderDispatcher.tryExtractRenderState` while [active]. Returns null for chests drawn from
     * the cache (vanilla then neither submits nor tessellates them) and the vanilla result for everything else.
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
        if (blockEntity !is ChestBlockEntity || breakProgress != null || isGloballyRendered) {
            return vanilla(original, blockEntity, partialTicks, breakProgress, isGloballyRendered, frustum)
        }
        return try {
            extractChest(dispatcher, blockEntity, partialTicks, frustum, cameraPos, original)
        } catch (e: Exception) {
            fail(e)
            vanilla(original, blockEntity, partialTicks, breakProgress, isGloballyRendered, frustum)
        }
    }

    private fun extractChest(
        dispatcher: BlockEntityRenderDispatcher,
        blockEntity: ChestBlockEntity,
        partialTicks: Float,
        frustum: Frustum?,
        cameraPos: Vec3,
        original: Operation<BlockEntityRenderState?>
    ): BlockEntityRenderState? {
        val breakProgress: ModelFeatureRenderer.CrumblingOverlay? = null
        val isGloballyRendered = false
        val level = blockEntity.level
        val renderer = dispatcher.getRenderer<BlockEntity, BlockEntityRenderState>(blockEntity)
        if (level == null || renderer == null || !blockEntity.type.isValid(blockEntity.blockState)) {
            return vanilla(original, blockEntity, partialTicks, breakProgress, isGloballyRendered, frustum)
        }
        // Same culling as the extraction it replaces.
        if (renderer.shouldRenderOffScreen() || !renderer.shouldRender(blockEntity, cameraPos)) return null
        /*? if neoforge {*/
        /*if (frustum != null && !frustum.isVisible(renderer.getRenderBoundingBox(blockEntity))) return null
        *//*?}*/

        val state = blockEntity.blockState
        if (!isClosed(blockEntity, level, state, partialTicks)) {
            return vanilla(original, blockEntity, partialTicks, breakProgress, isGloballyRendered, frustum)
        }

        val pos = blockEntity.blockPos
        val hasNeighbour = setNeighbour(pos, state)
        val lightVersion = lightVersion(pos, hasNeighbour)
        var entry = entries.get(pos.asLong())
        if (entry != null && (entry.blockEntity !== blockEntity || entry.blockState !== state)) {
            remove(entry)
            entry = null
        }
        // Light is read again after a light update touched the chest's section (or its other half's), and every
        // REVALIDATE_FRAMES frames anyway: light data sent by the server does not always announce itself.
        if (entry != null && (entry.lightVersion != lightVersion || (frame + entry.stagger) and REVALIDATE_MASK == 0L)) {
            val light = LightCoordsUtil.getLightCoords(level, pos)
            val neighbourLight = if (hasNeighbour) LightCoordsUtil.getLightCoords(level, neighbour) else 0
            if (entry.light == light && entry.neighbourLight == neighbourLight) {
                entry.lightVersion = lightVersion
            } else {
                remove(entry)
                entry = null
            }
        }

        if (entry == null) {
            val vanilla = vanilla(original, blockEntity, partialTicks, null, false, frustum) ?: return null
            val light = LightCoordsUtil.getLightCoords(level, pos)
            val neighbourLight = if (hasNeighbour) LightCoordsUtil.getLightCoords(level, neighbour) else 0
            entry = capture(dispatcher, blockEntity, state, vanilla, light, neighbourLight)
            entry.lightVersion = lightVersion
            entries.put(pos.asLong(), entry)
            if (!entry.cacheable) return vanilla
        } else if (!entry.cacheable) {
            return vanilla(original, blockEntity, partialTicks, breakProgress, isGloballyRendered, frustum)
        }

        entry.seenFrame = frame
        entry.markVisible(frame)
        return null
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

    private fun isClosed(chest: ChestBlockEntity, level: Level, state: BlockState, partialTicks: Float): Boolean {
        if (chest.getOpenNess(partialTicks) != 0f) return false
        if (!setNeighbour(chest.blockPos, state)) return true
        val other = level.getBlockEntity(neighbour) as? ChestBlockEntity ?: return true
        return other.getOpenNess(partialTicks) == 0f
    }

    private fun lightVersion(pos: BlockPos, hasNeighbour: Boolean): Int {
        if (lightVersions.isEmpty()) return 0
        var version = lightVersions.get(SectionPos.asLong(pos.x shr 4, pos.y shr 4, pos.z shr 4))
        if (hasNeighbour) version += lightVersions.get(SectionPos.asLong(neighbour.x shr 4, neighbour.y shr 4, neighbour.z shr 4))
        return version
    }

    /** Called from `ClientChunkCache.onLightUpdate`: cached chests in the section re-read their light. */
    @JvmStatic
    fun onLightUpdate(sectionX: Int, sectionY: Int, sectionZ: Int) {
        if (!active) return
        lightVersions.addTo(SectionPos.asLong(sectionX, sectionY, sectionZ), 1)
    }

    /** Points [neighbour] at the other half of a double chest; false for single chests. */
    private fun setNeighbour(pos: BlockPos, state: BlockState): Boolean {
        if (!state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return false
        neighbour.setWithOffset(pos, ChestBlock.getConnectedDirection(state))
        return true
    }

    // endregion

    // region capture

    private fun capture(
        dispatcher: BlockEntityRenderDispatcher,
        chest: ChestBlockEntity,
        state: BlockState,
        vanilla: BlockEntityRenderState,
        light: Int,
        neighbourLight: Int
    ): ChestEntry {
        val pos = chest.blockPos
        val originX = pos.x and 15.inv()
        val originY = pos.y and 15.inv()
        val originZ = pos.z and 15.inv()

        val meshes = try {
            collectSubmits(dispatcher, vanilla, pos, originX, originY, originZ)
            if (collectedUnsupported || collected.isEmpty()) null else tessellate()
        } catch (e: Exception) {
            // This chest stays on vanilla; repeated failures switch chests off for the session.
            Mobium.LOGGER.warn("Could not capture the chest at {}; it stays on the vanilla path", pos, e)
            Fallback.typeFailed(Module.STATIC_CACHE, TYPE, e)
            if (!chestsAllowed()) ModuleStates.refresh("chests failed")
            null
        } finally {
            collected.clear()
        }
        if (meshes.isNullOrEmpty()) return ChestEntry(chest, state, light, neighbourLight, emptyList(), false)

        val sectionKey = SectionPos.asLong(pos.x shr 4, pos.y shr 4, pos.z shr 4)
        val parts = meshes.map { mesh ->
            val bucket = bucket(sectionKey, originX, originY, originZ, mesh.renderType)
            val member = BucketMember(mesh)
            bucket.add(member)
            markDirty(bucket)
            ChestPart(bucket, member)
        }
        return ChestEntry(chest, state, light, neighbourLight, parts, true)
    }

    /** Runs vanilla's submit for the extracted state, in section-local space, and keeps the model submits. */
    private fun collectSubmits(
        dispatcher: BlockEntityRenderDispatcher,
        vanilla: BlockEntityRenderState,
        pos: BlockPos,
        originX: Int,
        originY: Int,
        originZ: Int
    ) {
        collectedUnsupported = false
        val poseStack = PoseStack()
        poseStack.translate((pos.x - originX).toFloat(), (pos.y - originY).toFloat(), (pos.z - originZ).toFloat())
        val camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState
        dispatcher.submit(vanilla, poseStack, storage, camera)

        val solid = storage.order(0).solid
        storage.drainPhases { phase ->
            if (phase !== solid) collectedUnsupported = true
            phase.sortInto(SubmitCollector)
        }
    }

    /** Lets vanilla's `ModelFeatureRenderer.prepareModel` write the collected submits into the capture buffers. */
    private fun tessellate(): List<CapturedMesh> {
        CaptureConsumer.begin()
        try {
            (modelRenderer as Any as RenderTypeFeatureRendererInvoker).`mobium$buildGroup`(null, collected)
        } catch (e: Throwable) {
            CaptureConsumer.end().forEach(CapturedMesh::free)
            throw e
        }
        val meshes = CaptureConsumer.end()
        val supported = meshes.all { it.renderType.primitiveTopology() == PrimitiveTopology.QUADS && !it.renderType.hasBlending() }
        if (!supported) {
            meshes.forEach(CapturedMesh::free)
            return emptyList()
        }
        return meshes
    }

    private object SubmitCollector : FeatureRenderPhase.Output {
        override fun accept(submit: SubmitNode, strictlyOrdered: Boolean) {
            if (submit is ModelFeatureRenderer.Submit<*>) collected += submit else collectedUnsupported = true
        }

        override fun <Submit : SubmitNode> acceptFeatureGroup(
            featureType: FeatureRendererType<Submit>,
            submits: Collection<Submit>,
            strictlyOrdered: Boolean
        ) {
            for (submit in submits) accept(submit, strictlyOrdered)
        }
    }

    // endregion

    // region buckets

    private fun bucket(sectionKey: Long, originX: Int, originY: Int, originZ: Int, renderType: RenderType): SectionBucket {
        val byType = buckets.get(sectionKey) ?: HashMap<RenderType, SectionBucket>().also { buckets.put(sectionKey, it) }
        return byType.getOrPut(renderType) { SectionBucket(sectionKey, originX, originY, originZ, renderType) }
    }

    private fun markDirty(bucket: SectionBucket) {
        if (!bucket.dirty) {
            bucket.dirty = true
            dirtyBuckets += bucket
        }
    }

    private fun remove(entry: ChestEntry) {
        for (part in entry.parts) {
            part.bucket.remove(part.member)
            part.member.mesh.free()
            markDirty(part.bucket)
        }
        entries.remove(entry.blockEntity.blockPos.asLong())
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
     * Runs right after the level's prepare stage, with the level's model-view matrix on the stack: rebuilds changed
     * buckets and prepares a render type per visible bucket with the section origin in the model-view matrix.
     */
    @JvmStatic
    fun prepare(cameraX: Double, cameraY: Double, cameraZ: Double) {
        if (!active) return
        if (shaderPackActive()) {
            ModuleStates.refresh("shader pack on")
            return
        }
        try {
            prepareBuckets(cameraX, cameraY, cameraZ)
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun prepareBuckets(cameraX: Double, cameraY: Double, cameraZ: Double) {
        for (bucket in dirtyBuckets) {
            if (bucket.isEmpty) {
                bucket.close()
                buckets.get(bucket.sectionKey)?.let { byType ->
                    byType.remove(bucket.renderType)
                    if (byType.isEmpty()) buckets.remove(bucket.sectionKey)
                }
            } else {
                bucket.rebuild()
            }
        }
        dirtyBuckets.clear()

        val modelView = RenderSystem.getModelViewStack()
        var maxVertices = 0
        for (bucket in visibleBuckets) {
            if (bucket.isEmpty) continue
            modelView.pushMatrix()
            modelView.translate(
                (bucket.originX - cameraX).toFloat(),
                (bucket.originY - cameraY).toFloat(),
                (bucket.originZ - cameraZ).toFloat()
            )
            bucket.prepared = bucket.renderType.prepare()
            modelView.popMatrix()
            maxVertices = maxOf(maxVertices, bucket.vertexCount)
        }
        Gpu.backend.requestQuadIndices(maxVertices)

        if (frame % SWEEP_INTERVAL_FRAMES == 0L) evictUnseen()
    }

    /** Draws the visible cached chests; called at the end of the level's solid pass. */
    @JvmStatic
    fun draw(renderPass: RenderPass) {
        var drawn = 0
        if (active) {
            try {
                for (bucket in visibleBuckets) {
                    drawn += bucket.draw(renderPass, frame)
                    bucket.prepared = null
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
        visibleBuckets.clear()
        lastDrawn = drawn
        frame++
    }

    private fun evictUnseen() {
        val stale = entries.values.filter { frame - it.seenFrame > EVICT_AFTER_FRAMES }
        stale.forEach(::remove)
    }

    /** Drops every cached chest and GPU buffer (level change, reload, render distance change, toggle off). */
    @JvmStatic
    fun clear() {
        for (entry in entries.values) for (part in entry.parts) part.member.mesh.free()
        entries.clear()
        lightVersions.clear()
        for (byType in buckets.values) for (bucket in byType.values) bucket.close()
        buckets.clear()
        dirtyBuckets.clear()
        visibleBuckets.clear()
    }

    fun debugLine(): String? {
        if (!config.cache.prototype) return null
        val bucketCount = buckets.values.sumOf { it.size }
        return "Mobium chest prototype ${if (active) "on" else "off"}: $cachedCount cached, $lastDrawn drawn, $bucketCount buckets"
    }

    // endregion
}

/** One cached chest: the inputs its capture depends on and its meshes in the section buckets. */
class ChestEntry(
    @JvmField val blockEntity: ChestBlockEntity,
    @JvmField val blockState: BlockState,
    @JvmField val light: Int,
    @JvmField val neighbourLight: Int,
    @JvmField val parts: List<ChestPart>,
    @JvmField val cacheable: Boolean
) {
    @JvmField
    var seenFrame = 0L

    /** The section light versions the stored [light] values were last checked against. */
    @JvmField
    var lightVersion = 0

    /** Spreads the periodic light re-check of all chests over the frames. */
    @JvmField
    val stagger = (blockEntity.blockPos.asLong() * -7046029254386353131L ushr 57)

    fun markVisible(frame: Long) {
        for (i in parts.indices) {
            val part = parts[i]
            part.member.visibleFrame = frame
            ChestPrototype.markVisible(part.bucket)
        }
    }
}

class ChestPart(@JvmField val bucket: SectionBucket, @JvmField val member: BucketMember)
