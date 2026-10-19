package dev.nyon.mobium.mixins;

import dev.nyon.mobium.MobiumKeys;
import dev.nyon.mobium.bench.FrameRecorder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the frame boundary (one {@code runTick} per game-loop iteration) for Mobium's timers and the benchmark
 * recorder, and polls Mobium's key bindings once per client tick.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void mobium$frameStart(boolean advanceGameTime, CallbackInfo ci) {
        FrameRecorder.onFrameStart(System.nanoTime());
    }

    // Handles Mobium's key bindings once per client tick, after vanilla's own input handling. No behaviour change.
    @Inject(method = "tick", at = @At("TAIL"))
    private void mobium$handleKeys(CallbackInfo ci) {
        MobiumKeys.tick((Minecraft) (Object) this);
    }
}
