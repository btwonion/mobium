package dev.nyon.mobium.cache

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.CompatMod
import dev.nyon.mobium.core.EmfCompat
import dev.nyon.mobium.mixins.FeatureRenderDispatcherAccessor
import dev.nyon.mobium.mixins.RenderTypeFeatureRendererInvoker
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeStorage
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.entity.EntityRenderDispatcher
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer
import net.minecraft.client.renderer.feature.FeatureFrameContext
import net.minecraft.client.renderer.feature.FeatureRendererType
import net.minecraft.client.renderer.feature.FlameFeatureRenderer
import net.minecraft.client.renderer.feature.ItemFeatureRenderer
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer
import net.minecraft.client.renderer.feature.TextFeatureRenderer
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase
import net.minecraft.client.renderer.feature.submit.SubmitNode
import net.minecraft.client.renderer.item.ItemStackRenderState

/**
 * Captures one object by running vanilla's own submit and prepare code for it (docs/vanilla-notes.md §13): the
 * renderer submits into a private [SubmitNodeStorage], and vanilla's feature renderers write the vertices into
 * [CaptureConsumer]. Every mod hook on that path runs as usual (EMF/ETF, Iris's entity ids).
 *
 * Only what vanilla draws in the level's **solid** phase is accepted: model submits, block models (item frames, block
 * displays), the solid quads of items and opaque text (no background), as `SubmitNodeCollection` sorts them while the
 * level renders. Anything else (translucent models, blocks or item quads, crumbling, custom geometry, outlines, the
 * special glint) makes the capture fail and the object stays on vanilla, as do models EMF animates. Render thread only.
 */
internal object Capture {
    private val storage = SubmitNodeStorage()
    private val modelRenderer = ModelFeatureRenderer()
    private val textRenderer = TextFeatureRenderer()
    private val itemRenderer = ItemFeatureRenderer()
    private val blockModelRenderer = BlockModelFeatureRenderer()
    private val models = ArrayList<ModelFeatureRenderer.Submit<*>>()
    private val items = ArrayList<ItemFeatureRenderer.Submit>()
    private val texts = ArrayList<TextFeatureRenderer.Submit>()
    private val blockModels = ArrayList<BlockModelFeatureRenderer.Submit>()

    /** Submits of loader APIs ([ExtendedSubmits]), by the game's feature renderer registered for their type. */
    private val extended = LinkedHashMap<RenderTypeFeatureRenderer<*>, MutableList<SubmitNode>>()
    private var unsupported = false

    /**
     * True while an entity is captured: the capture submits through `EntityRenderDispatcher.submit`, whose renderer
     * call the cache wraps ([StaticCache.entity] then lets vanilla run), and the flame and shadow the dispatcher
     * submits are left out (vanilla's own submit draws them every frame, cached or not).
     */
    @JvmField
    var capturingEntity = false

    /** Why the last capture returned null (debug output only; set on the failure paths). */
    var lastRejection: String? = null
        private set

    private fun reject(reason: String) {
        if (!unsupported) lastRejection = reason
        unsupported = true
    }

    /**
     * Captures a block entity from the render state vanilla extracted this frame, in block-local space (the block's
     * origin at 0, 0, 0). Returns null when anything was submitted that the cache cannot draw.
     *
     * Must run inside `LevelRenderer.render`, as vanilla's own submit does: text only goes into the solid phase while
     * `RenderSystem.isRenderingLevel` (`SubmitNodeCollection.canRenderAsSolid`), and with a shader pack Iris extends
     * the vertex formats, wraps block entity render types and records the entity ids only there.
     */
    fun blockEntity(dispatcher: BlockEntityRenderDispatcher, state: BlockEntityRenderState): List<CapturedMesh>? {
        check(RenderSystem.isRenderingLevel) { "Captures run inside the level render" }
        try {
            collect { dispatcher.submit(state, PoseStack(), storage, cameraState()) }
            return if (unsupported || nothingCollected()) null else tessellate()
        } finally {
            clearCollected()
        }
    }

    /**
     * Captures an entity from the render state vanilla extracted this frame, in entity-local space (the state's x, y,
     * z at 0, 0, 0; the renderer's render offset is part of the mesh). It goes through `EntityRenderDispatcher.submit`
     * like vanilla's own submit, because Iris records the entity id there; returns null when anything was submitted
     * that the cache cannot draw. Must run inside `LevelRenderer.render`, like [blockEntity].
     */
    fun entity(dispatcher: EntityRenderDispatcher, state: EntityRenderState): List<CapturedMesh>? {
        check(RenderSystem.isRenderingLevel) { "Captures run inside the level render" }
        capturingEntity = true
        try {
            collect { dispatcher.submit(state, cameraState(), 0.0, 0.0, 0.0, PoseStack(), storage) }
            return if (unsupported || nothingCollected()) null else tessellate()
        } finally {
            capturingEntity = false
            clearCollected()
        }
    }

    private fun nothingCollected(): Boolean {
        val empty = models.isEmpty() && items.isEmpty() && texts.isEmpty() && blockModels.isEmpty() && extended.isEmpty()
        if (empty) lastRejection = "nothing submitted"
        return empty
    }

    private fun clearCollected() {
        models.clear()
        items.clear()
        texts.clear()
        blockModels.clear()
        extended.clear()
    }

    /** Collects a loader API's submit for the feature renderer the game registered for its type. */
    private fun collectExtended(submit: SubmitNode) {
        val dispatcher = Minecraft.getInstance().gameRenderer.featureRenderDispatcher() as FeatureRenderDispatcherAccessor
        val renderer = dispatcher.`mobium$featureRenderers`().get(submit.featureType())
        if (renderer is RenderTypeFeatureRenderer<*>) {
            extended.getOrPut(renderer) { ArrayList() } += submit
        } else {
            reject("no renderer for ${describe(submit)}")
        }
    }

    /** A submit's class and render type, for rejection reasons. */
    private fun describe(submit: SubmitNode): String {
        val type = when (submit) {
            is ModelFeatureRenderer.Submit<*> -> submit.renderType()
            is BlockModelFeatureRenderer.Submit -> submit.renderType()
            else -> null
        }
        return submit.javaClass.name.substringAfterLast('.') + (type?.let { " $it" } ?: "")
    }

    private inline fun collect(submit: () -> Unit) {
        unsupported = false
        lastRejection = null
        submit()
        val solid = storage.order(0).solid
        storage.drainPhases { phase -> phase.sortInto(if (phase === solid) Collector else Rejecter) }
    }

    private fun tessellate(): List<CapturedMesh>? {
        CaptureConsumer.begin()
        try {
            if (models.isNotEmpty()) buildGroup(modelRenderer, null, models)
            // BlockModelFeatureRenderer.buildGroup does not read the context.
            if (blockModels.isNotEmpty()) buildGroup(blockModelRenderer, null, blockModels)
            for ((renderer, submits) in extended) buildGroup(renderer, frameContext(), submits)
            if (items.isNotEmpty()) buildGroup(itemRenderer, frameContext(), items)
            if (texts.isNotEmpty()) buildGroup(textRenderer, frameContext(), texts)
        } catch (e: Throwable) {
            CaptureConsumer.abort()
            throw e
        }
        val meshes = CaptureConsumer.end()
        if (meshes.isEmpty() || !meshes.all { it.drawable }) {
            lastRejection = if (meshes.isEmpty()) "no vertices" else "not drawable: " +
                meshes.filter { !it.drawable }.joinToString { "${it.renderType} ${it.topology} ${it.format}" }
            meshes.forEach(CapturedMesh::free)
            return null
        }
        return meshes
    }

    /** Runs a feature renderer's own vertex writing (`RenderTypeFeatureRenderer.buildGroup`) for [submits]. */
    private fun buildGroup(renderer: Any, context: FeatureFrameContext?, submits: List<*>) =
        (renderer as RenderTypeFeatureRendererInvoker).`mobium$buildGroup`(context, submits)

    private fun cameraState() =
        Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState

    /** The same context `FeatureRenderDispatcher.prepareFrame` builds; text needs its font. */
    private fun frameContext(): FeatureFrameContext {
        val minecraft = Minecraft.getInstance()
        val gameRenderer = minecraft.gameRenderer
        return FeatureFrameContext(
            gameRenderer.gameRenderState().optionsRenderState,
            minecraft.font,
            minecraft.modelManager.blockStateModelSet,
            minecraft.blockColors,
            minecraft.textureManager,
            minecraft.atlasManager,
            gameRenderer.lightmap(),
            gameRenderer.renderBuffers().stagedVertexBuffer()
        )
    }

    private val emfLoaded = Compat.isLoaded(CompatMod.EMF)

    private object Collector : FeatureRenderPhase.Output {
        override fun accept(submit: SubmitNode, strictlyOrdered: Boolean) {
            when (submit) {
                // A model EMF animates would freeze in the cache; it stays on vanilla.
                is ModelFeatureRenderer.Submit<*> -> {
                    if (emfLoaded && EmfCompat.isAnimated(submit.model())) reject("model EMF animates") else models += submit
                }
                // The special glint takes its UVs from the submit's camera-relative pose (computeFoilDecalPose).
                is ItemFeatureRenderer.Submit -> {
                    if (submit.foilType() == ItemStackRenderState.FoilType.SPECIAL) reject("special glint") else items += submit
                }
                is TextFeatureRenderer.Submit -> texts += submit
                is BlockModelFeatureRenderer.Submit -> {
                    if (submit.sheetedDecalPose() != null) reject("block model with a decal") else blockModels += submit
                }
                is FlameFeatureRenderer.Submit -> if (!capturingEntity) reject("flame")
                else -> if (ExtendedSubmits.isPlainBlockModel(submit)) collectExtended(submit) else reject("solid ${describe(submit)}")
            }
        }

        override fun <Submit : SubmitNode> acceptFeatureGroup(
            featureType: FeatureRendererType<Submit>,
            submits: Collection<Submit>,
            strictlyOrdered: Boolean
        ) {
            for (submit in submits) accept(submit, strictlyOrdered)
        }
    }

    /** Every phase but the solid one: only an entity's shadow may be there (vanilla draws it every frame). */
    private object Rejecter : FeatureRenderPhase.Output {
        override fun accept(submit: SubmitNode, strictlyOrdered: Boolean) {
            if (!capturingEntity || submit !is ShadowFeatureRenderer.Submit) reject("outside order 0's solid phase: ${describe(submit)}")
        }

        override fun <Submit : SubmitNode> acceptFeatureGroup(
            featureType: FeatureRendererType<Submit>,
            submits: Collection<Submit>,
            strictlyOrdered: Boolean
        ) {
            for (submit in submits) accept(submit, strictlyOrdered)
        }
    }
}
