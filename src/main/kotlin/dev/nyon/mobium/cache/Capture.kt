package dev.nyon.mobium.cache

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import dev.nyon.mobium.mixins.RenderTypeFeatureRendererInvoker
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeStorage
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.FeatureFrameContext
import net.minecraft.client.renderer.feature.FeatureRendererType
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.feature.TextFeatureRenderer
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase
import net.minecraft.client.renderer.feature.submit.SubmitNode

/**
 * Captures one object by running vanilla's own submit and prepare code for it (docs/vanilla-notes.md §13): the
 * renderer submits into a private [SubmitNodeStorage], and vanilla's feature renderers write the vertices into
 * [CaptureConsumer]. Every mod hook on that path runs as usual (EMF/ETF, Iris's entity ids).
 *
 * Only what vanilla draws in the level's **solid** phase is accepted: model submits and opaque text (no background),
 * as `SubmitNodeCollection` sorts them while the level renders. Anything else (translucent models, crumbling, items,
 * custom geometry) makes the capture fail and the object stays on vanilla. Render thread only.
 */
internal object Capture {
    private val storage = SubmitNodeStorage()
    private val modelRenderer = ModelFeatureRenderer()
    private val textRenderer = TextFeatureRenderer()
    private val models = ArrayList<ModelFeatureRenderer.Submit<*>>()
    private val texts = ArrayList<TextFeatureRenderer.Submit>()
    private var unsupported = false

    /**
     * Captures a block entity from the render state vanilla extracted this frame, in block-local space (the block's
     * origin at 0, 0, 0). Returns null when anything was submitted that the cache cannot draw.
     *
     * Must run inside `LevelRenderer.render`, as vanilla's own submit does: text only goes into the solid phase while
     * `RenderSystem.isRenderingLevel` (`SubmitNodeCollection.canRenderAsSolid`), and with a shader pack Iris extends the
     * vertex formats, wraps block entity render types and records the entity ids only there.
     */
    fun blockEntity(dispatcher: BlockEntityRenderDispatcher, state: BlockEntityRenderState): List<CapturedMesh>? {
        check(RenderSystem.isRenderingLevel) { "Captures run inside the level render" }
        try {
            collect { dispatcher.submit(state, PoseStack(), storage, cameraState()) }
            if (unsupported || (models.isEmpty() && texts.isEmpty())) return null
            return tessellate()
        } finally {
            models.clear()
            texts.clear()
        }
    }

    private inline fun collect(submit: () -> Unit) {
        unsupported = false
        submit()
        val solid = storage.order(0).solid
        storage.drainPhases { phase ->
            if (phase !== solid) unsupported = true
            phase.sortInto(Collector)
        }
    }

    private fun tessellate(): List<CapturedMesh>? {
        CaptureConsumer.begin()
        try {
            if (models.isNotEmpty()) (modelRenderer as Any as RenderTypeFeatureRendererInvoker).`mobium$buildGroup`(null, models)
            if (texts.isNotEmpty()) (textRenderer as Any as RenderTypeFeatureRendererInvoker).`mobium$buildGroup`(frameContext(), texts)
        } catch (e: Throwable) {
            CaptureConsumer.abort()
            throw e
        }
        val meshes = CaptureConsumer.end()
        if (meshes.isEmpty() || !meshes.all { it.drawable }) {
            meshes.forEach(CapturedMesh::free)
            return null
        }
        return meshes
    }

    private fun cameraState() = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState

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

    private object Collector : FeatureRenderPhase.Output {
        override fun accept(submit: SubmitNode, strictlyOrdered: Boolean) {
            when (submit) {
                is ModelFeatureRenderer.Submit<*> -> models += submit
                is TextFeatureRenderer.Submit -> texts += submit
                else -> unsupported = true
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
}
