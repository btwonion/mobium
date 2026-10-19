package dev.nyon.mobium.debug

/** Added to `ModelFeatureRenderer.Submit` by a mixin: remembers whether a block entity submitted the model. */
@Suppress("FunctionName")
interface TimedSubmit {
    fun `mobium$isBlockEntity`(): Boolean
}
