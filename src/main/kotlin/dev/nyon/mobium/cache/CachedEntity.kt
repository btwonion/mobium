package dev.nyon.mobium.cache

import net.minecraft.world.entity.Entity

/**
 * The static cache's entry for an entity, kept on the entity itself (`EntityMixin`) so that the per-frame check finds
 * it without a map lookup. Outside the mixin package, which Mixin does not let other classes load from.
 */
@Suppress("FunctionName")
interface CachedEntity {
    fun `mobium$cacheEntry`(): Any?

    fun `mobium$setCacheEntry`(entry: Any?)
}

/**
 * The entity a render state was extracted from (`EntityRenderStateMixin`, set by `EntityRenderDispatcherMixin` after
 * `extractEntity`); vanilla's render states carry no reference to it. Null for states made elsewhere.
 */
@Suppress("FunctionName")
interface EntityStateSource {
    fun `mobium$entity`(): Entity?

    fun `mobium$setEntity`(entity: Entity?)
}
