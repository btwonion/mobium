package dev.nyon.mobium.core

import net.irisshaders.iris.Iris
import net.irisshaders.iris.api.v0.IrisApi

/** Calls into Iris's public API. Only call these after checking `Compat.isLoaded(CompatMod.IRIS)`. */
object IrisCompat {
    fun isShaderPackInUse(): Boolean = IrisApi.getInstance().isShaderPackInUse

    fun isRenderingShadowPass(): Boolean = IrisApi.getInstance().isRenderingShadowPass

    /**
     * The rendering pipeline Iris uses right now (null before the first level). Iris replaces it on every reload,
     * shader pack switch and pack option change (`Iris.reload` → `PipelineManager.destroyPipeline`), so its identity
     * tells when vertex formats and entity ids may have changed. Not part of Iris's API; Iris 1.11.7.
     */
    fun pipeline(): Any? = Iris.getPipelineManager().pipelineNullable
}
