package dev.nyon.mobium.mixins;

import dev.nyon.mobium.MobiumKeys;
import dev.nyon.mobium.bench.BenchAutorun;
import dev.nyon.mobium.bench.BenchSuite;
import dev.nyon.mobium.bench.FrameRecorder;
import dev.nyon.mobium.debug.ExpandProfile;
import dev.nyon.mobium.debug.Validation;
import dev.nyon.mobium.debug.VulkanValidation;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the frame boundary (one {@code runTick} per game-loop iteration) for Mobium's timers, the benchmark recorder
 * and the validation frame pairs, the frame's submit for Module 2's dev GPU timer, and polls Mobium's key bindings once
 * per client tick, and removes the dev Vulkan validation messenger at shutdown.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void mobium$frameStart(boolean advanceGameTime, CallbackInfo ci) {
        FrameRecorder.onFrameStart(System.nanoTime());
        Validation.onFrameStart();
        ExpandProfile.frameStart();
    }

    // Dev measurement only (ExpandProfile, off unless the self-check runs it): a GPU timestamp before the frame's
    // commands are submitted. No behaviour change.
    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V"))
    private void mobium$beforeSubmit(boolean advanceGameTime, CallbackInfo ci) {
        ExpandProfile.frameEnd();
    }

    // Dev tool only (VulkanValidation, which listens only when a dev client asked for the validation layer): removes
    // Mobium's debug messenger before the renderer destroys the Vulkan instance. No behaviour change.
    @Inject(method = "close", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;shutdownRenderer()V"))
    private void mobium$beforeRendererShutdown(CallbackInfo ci) {
        VulkanValidation.uninstall();
    }

    // Handles Mobium's key bindings and advances a running benchmark suite and an unattended round (BenchAutorun) once
    // per client tick. No behaviour change.
    @Inject(method = "tick", at = @At("TAIL"))
    private void mobium$handleKeys(CallbackInfo ci) {
        MobiumKeys.tick((Minecraft) (Object) this);
        BenchSuite.tick((Minecraft) (Object) this);
        BenchAutorun.tick((Minecraft) (Object) this);
    }
}
