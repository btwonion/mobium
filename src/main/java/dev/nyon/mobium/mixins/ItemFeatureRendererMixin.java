package dev.nyon.mobium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.QuadInstance;
import dev.nyon.mobium.parts.ItemTransform;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Module 2 for items: an item submit's quads become records for the GPU expand pass instead of vertices written on the
 * CPU ({@code ItemFeatureRenderer.prepareMainSubmit}: per quad {@code VertexConsumer.putBakedQuad}, or Sodium's
 * {@code BakedModelEncoder}). Iris's ids are set around it in {@code prepareSubmit}, outline submits are not touched.
 * Fallback: {@code ItemTransform.prepare} returns false for anything it does not take (module or switch off, excluded
 * entity types, the special glint, quads it cannot reproduce) and vanilla writes the submit; runs of quads it cannot
 * take inside a submit it writes with vanilla's {@code putBakedQuad} itself.
 */
@Mixin(ItemFeatureRenderer.class)
public class ItemFeatureRendererMixin {
    @Shadow
    @Final
    private QuadInstance quadInstance;

    @WrapMethod(method = "prepareMainSubmit")
    private void mobium$prepareMainSubmit(ItemFeatureRenderer.Submit submit, Operation<Void> original) {
        if (!ItemTransform.prepare((ItemFeatureRenderer) (Object) this, submit, this.quadInstance)) original.call(submit);
    }
}
