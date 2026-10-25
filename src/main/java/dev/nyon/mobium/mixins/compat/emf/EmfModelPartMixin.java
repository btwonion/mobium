package dev.nyon.mobium.mixins.compat.emf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nyon.mobium.debug.Sections;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The same measurement switch as {@code ModelPartMixin} for EMF's parts, whose {@code EMFModelPart.compile} overrides
 * vanilla's (EMF 3.3.12 makes {@code ModelPart.compile} extendable). Optional, so another EMF version only loses the
 * measurement.
 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.parts.EMFModelPart", remap = false)
public class EmfModelPartMixin {
    @Inject(method = "compile", at = @At("HEAD"), cancellable = true, require = 0)
    private void mobium$skipVertices(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color, CallbackInfo ci) {
        if (Sections.skipModelVertices) ci.cancel();
    }
}
