package dev.nyon.mobium.mixins.compat.emf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nyon.mobium.mixins.ModelPartAccessor;
import dev.nyon.mobium.parts.PartTransform;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Module 2 for EMF's parts: EMF 3.3.12 makes {@code ModelPart.compile} extendable and {@code EMFModelPart.compile}
 * overrides it (Fresh Animations' cubes are EMF's own {@code EMFCube}s), so {@code ModelPartMixin} never sees them.
 * Same contract: {@code PartTransform.compile} takes the part or returns false and EMF compiles. Optional, so another
 * EMF version only loses Module 2 for its parts.
 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.parts.EMFModelPart", remap = false)
public class EmfModelPartMixin {
    @WrapMethod(method = "compile", require = 0)
    private void mobium$compile(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color, Operation<Void> original) {
        if (!PartTransform.compile(((ModelPartAccessor) this).mobium$cubes(), pose, buffer, light, overlay, color)) {
            original.call(pose, buffer, light, overlay, color);
        } else if (PartTransform.validating) {
            original.call(pose, PartTransform.validationConsumer(), light, overlay, color);
            PartTransform.endValidation();
        }
    }
}
