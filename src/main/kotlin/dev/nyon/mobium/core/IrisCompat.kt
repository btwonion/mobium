package dev.nyon.mobium.core

import com.mojang.renderpearl.api.vertex.VertexFormat
import net.irisshaders.iris.Iris
import net.irisshaders.iris.api.v0.IrisApi
import net.irisshaders.iris.uniforms.CapturedRenderingState
import net.irisshaders.iris.vertices.ImmediateState
import net.irisshaders.iris.vertices.IrisVertexFormats

/**
 * Calls into Iris's public API. Only call these after checking `Compat.isLoaded(CompatMod.IRIS)`. The 26.4 target
 * compiles against Iris for 26.3 (there is none for 26.4), where Iris cannot load.
 */
object IrisCompat {
    fun isShaderPackInUse(): Boolean = IrisApi.getInstance().isShaderPackInUse

    /** The selected shader pack's file name (for `/mobium benchmark`'s CSV). */
    fun shaderPackName(): String = Iris.getCurrentPackName() ?: ""

    fun isRenderingShadowPass(): Boolean = IrisApi.getInstance().isRenderingShadowPass

    /**
     * The rendering pipeline Iris uses right now (its `VanillaRenderingPipeline` until a level or pack loads, and
     * for good while Iris does not run on Vulkan, docs/vanilla-notes.md §21). Iris replaces it on every reload,
     * shader pack switch and pack option change (`Iris.reload` → `PipelineManager.destroyPipeline`), so its identity
     * tells when vertex formats and entity ids may have changed. Not part of Iris's API; Iris 1.11.7.
     */
    fun pipeline(): Any? = Iris.getPipelineManager().pipelineNullable

    /** Iris's 56-byte entity format (`IrisVertexFormats.ENTITY`), which builders get while a pack renders the level. */
    //? if >=26.4 {
    /*fun entityFormat(): VertexFormat = throw UnsupportedOperationException("Iris has no build for 26.4")
    *///?} else
    fun entityFormat(): VertexFormat = IrisVertexFormats.ENTITY

    /** `ImmediateState.isRenderingLevel`: whether Iris extends vertices for the level (and replaces their normals). */
    @JvmStatic
    fun isRenderingLevel(): Boolean = ImmediateState.isRenderingLevel

    // The ids Iris writes into each vertex (`iris_Entity`), as its BufferBuilder hook reads them.
    @JvmStatic
    fun currentEntity(): Int = CapturedRenderingState.INSTANCE.currentRenderedEntity

    @JvmStatic
    fun currentBlockEntity(): Int = CapturedRenderingState.INSTANCE.currentRenderedBlockEntity

    @JvmStatic
    fun currentItem(): Int = CapturedRenderingState.INSTANCE.currentRenderedItem
}
