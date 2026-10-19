package dev.nyon.mobium.core

import net.irisshaders.iris.api.v0.IrisApi

/** Calls into Iris's public API. Only call these after checking `Compat.isLoaded(CompatMod.IRIS)`. */
object IrisCompat {
    fun isShaderPackInUse(): Boolean = IrisApi.getInstance().isShaderPackInUse
}
