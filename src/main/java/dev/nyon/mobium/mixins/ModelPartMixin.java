package dev.nyon.mobium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nyon.mobium.parts.PartTransform;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * Module 2: a part's cubes become one record each for the GPU expand pass instead of vertices written on the CPU
 * ({@code ModelPart.compile}: vanilla's {@code Cube.compile} or Sodium's cube path). The part walk, posing and EMF's
 * hooks above stay as they are. Fallback: {@code PartTransform.compile} returns false for anything it does not take
 * (unknown consumer or format, sorted draws, other cube classes, module off) and vanilla compiles.
 */
@Mixin(ModelPart.class)
public class ModelPartMixin {
    @Shadow
    @Final
    private List<ModelPart.Cube> cubes;

    @WrapMethod(method = "compile")
    private void mobium$compile(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color, Operation<Void> original) {
        if (!PartTransform.compile(this.cubes, pose, buffer, light, overlay, color)) {
            original.call(pose, buffer, light, overlay, color);
        } else if (PartTransform.checkPart) {
            // The part is compared with the CPU's vertices (PartCheck, or the dev validation): compiled once more.
            try {
                original.call(pose, PartTransform.validationConsumer(), light, overlay, color);
            } catch (Throwable e) {
                PartTransform.validationFailed(e);
            } finally {
                PartTransform.endValidation();
            }
        }
    }
}
