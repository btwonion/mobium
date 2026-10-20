package dev.nyon.mobium.mixins;

import dev.nyon.mobium.cache.StaticCache;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Static cache (Module 1): a block entity removed from the client level (block broken or replaced, chunk unloaded)
 * drops its cached picture right away instead of waiting for the eviction sweep. No behaviour change; server-side
 * block entities are ignored.
 */
@Mixin(BlockEntity.class)
public class BlockEntityMixin {
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void mobium$dropCached(CallbackInfo ci) {
        if (StaticCache.active) StaticCache.onRemoved((BlockEntity) (Object) this);
    }
}
