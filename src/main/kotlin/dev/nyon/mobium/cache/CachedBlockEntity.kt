package dev.nyon.mobium.cache

/**
 * The static cache's entry for a block entity, kept on the block entity itself (`BlockEntityMixin`) so that the
 * per-frame check finds it without a map lookup. Outside the mixin package, which Mixin does not let other classes
 * load from.
 */
@Suppress("FunctionName")
interface CachedBlockEntity {
    fun `mobium$cacheEntry`(): Any?

    fun `mobium$setCacheEntry`(entry: Any?)
}
