package dev.nyon.mobium.mixins;

import dev.nyon.mobium.cache.CachedEntity;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Static cache (Module 1): holds the entity's cache entry ({@link CachedEntity}). Adds a field; no behaviour change.
 * Removed entities are no longer extracted, so their cached picture is not drawn; the cache's sweep frees it.
 */
@Mixin(Entity.class)
public class EntityMixin implements CachedEntity {
    @Unique
    private @Nullable Object mobium$cacheEntry;

    @Override
    public @Nullable Object mobium$cacheEntry() {
        return this.mobium$cacheEntry;
    }

    @Override
    public void mobium$setCacheEntry(@Nullable Object entry) {
        this.mobium$cacheEntry = entry;
    }
}
