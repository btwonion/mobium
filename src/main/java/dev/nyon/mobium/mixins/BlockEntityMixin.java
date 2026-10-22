package dev.nyon.mobium.mixins;

import dev.nyon.mobium.cache.CachedBlockEntity;
import dev.nyon.mobium.cache.StaticCache;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Static cache (Module 1): holds the block entity's cache entry ({@link CachedBlockEntity}), and a block entity removed
 * from the client level (block broken or replaced, chunk unloaded) drops its cached picture right away instead of
 * waiting for the eviction sweep. Adds a field; no behaviour change. Server-side block entities are ignored.
 */
@Mixin(BlockEntity.class)
public class BlockEntityMixin implements CachedBlockEntity {
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

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void mobium$dropCached(CallbackInfo ci) {
        if (StaticCache.active) StaticCache.onRemoved((BlockEntity) (Object) this);
    }
}
