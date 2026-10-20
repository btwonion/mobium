package dev.nyon.mobium.mixins;

import dev.nyon.mobium.cache.ChestPrototype;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chest prototype (M3): forwards client light updates so cached chests only re-read their light when their section
 * changed. Sodium overwrites the {@code LevelExtractor} methods this calls, not this one. No behaviour change.
 */
@Mixin(ClientChunkCache.class)
public class ClientChunkCacheMixin {
    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void mobium$onLightUpdate(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        ChestPrototype.onLightUpdate(pos.x(), pos.y(), pos.z());
    }
}
