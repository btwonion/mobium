package dev.nyon.mobium.mixins;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nyon.mobium.cache.CaptureConsumer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While Mobium captures an object for its cache, hands vanilla's feature renderers a capture buffer instead of the
 * frame's shared staged buffer. Priority 1100 so that mods changing the render type at the head of this method (ETF)
 * run first. Fallback: outside a capture the original method runs unchanged.
 */
@Mixin(value = RenderTypeFeatureRenderer.class, priority = 1100)
public class RenderTypeFeatureRendererMixin {
    @Inject(method = "getVertexBuilder", at = @At("HEAD"), cancellable = true)
    private void mobium$capture(RenderType renderType, CallbackInfoReturnable<VertexConsumer> cir) {
        if (CaptureConsumer.active) cir.setReturnValue(CaptureConsumer.vertexBuilder(renderType));
    }
}
