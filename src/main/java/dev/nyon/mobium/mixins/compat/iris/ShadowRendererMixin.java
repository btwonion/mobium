package dev.nyon.mobium.mixins.compat.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.nyon.mobium.debug.Sections;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times Iris's shadow pass ({@code ShadowRenderer.renderShadows}) and its own entity and block entity extraction,
 * submit and prepare, which run inside the level render but outside the level's stages, so the level's timers never
 * saw them (docs/vanilla-notes.md, *Shader-pack reload and shadow pass*). Measurement only, nothing changes: with
 * timers off the handlers only read a flag. Every injection is optional, so another Iris version only loses these
 * timers. Iris 1.11.7, the same on Fabric and NeoForge.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public class ShadowRendererMixin {
    @Inject(method = "renderShadows", at = @At("HEAD"), require = 0)
    private void mobium$beginShadowPass(CallbackInfo ci) {
        Sections.beginShadowPass();
    }

    @Inject(method = "renderShadows", at = @At("RETURN"), require = 0)
    private void mobium$endShadowPass(CallbackInfo ci) {
        Sections.endShadowPass();
    }

    @Inject(method = {"extractVisibleEntities", "extractVisibleBlockEntities"}, at = @At("HEAD"), require = 0)
    private void mobium$beginExtract(CallbackInfo ci) {
        Sections.open(Sections.SHADOW_EXTRACT);
    }

    @Inject(method = {"extractVisibleEntities", "extractVisibleBlockEntities"}, at = @At("RETURN"), require = 0)
    private void mobium$endExtract(CallbackInfo ci) {
        Sections.close(Sections.SHADOW_EXTRACT);
    }

    @Inject(method = "renderEntities", at = @At("HEAD"), require = 0)
    private void mobium$beginSubmitEntities(CallbackInfoReturnable<Integer> cir) {
        Sections.open(Sections.SHADOW_SUBMIT);
    }

    @Inject(method = "renderEntities", at = @At("RETURN"), require = 0)
    private void mobium$endSubmitEntities(CallbackInfoReturnable<Integer> cir) {
        Sections.close(Sections.SHADOW_SUBMIT);
    }

    // Tags the block entities' model submits as the level's submit loop does, for the shadow tessellation split.
    @Inject(method = "renderBlockEntities", at = @At("HEAD"), require = 0)
    private void mobium$beginSubmitBlockEntities(CallbackInfoReturnable<Integer> cir) {
        Sections.open(Sections.SHADOW_SUBMIT);
        Sections.submittingBlockEntities = true;
    }

    @Inject(method = "renderBlockEntities", at = @At("RETURN"), require = 0)
    private void mobium$endSubmitBlockEntities(CallbackInfoReturnable<Integer> cir) {
        Sections.submittingBlockEntities = false;
        Sections.close(Sections.SHADOW_SUBMIT);
    }

    @WrapOperation(
        method = "renderShadows",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
        ),
        require = 0
    )
    private FeatureRenderDispatcher.PreparedFrame mobium$timeShadowPrepare(
        FeatureRenderDispatcher dispatcher,
        SubmitNodeStorage storage,
        Operation<FeatureRenderDispatcher.PreparedFrame> original
    ) {
        long start = Sections.begin();
        Sections.inShadowPrepare = true;
        try {
            return original.call(dispatcher, storage);
        } finally {
            Sections.inShadowPrepare = false;
            Sections.end(Sections.SHADOW_PREPARE, start);
        }
    }
}
