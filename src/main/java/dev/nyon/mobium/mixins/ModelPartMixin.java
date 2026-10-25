package dev.nyon.mobium.mixins;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nyon.mobium.debug.Sections;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measurement for Module 2 (dev self-check only): while {@link Sections#skipModelVertices} is set, a part writes no
 * vertices ({@code ModelPart.compile}, which runs Sodium's cube path or vanilla's {@code Cube.compile}), so the frame
 * shows what the vertex work costs. The models vanish while it is set; otherwise nothing changes.
 */
@Mixin(ModelPart.class)
public class ModelPartMixin {
    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    private void mobium$skipVertices(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color, CallbackInfo ci) {
        if (Sections.skipModelVertices) ci.cancel();
    }
}
