package dev.nyon.mobium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nyon.mobium.debug.Sections;
import dev.nyon.mobium.debug.TimedSubmit;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Times posing ({@code Model.setupAnim}) and tessellation ({@code Model.renderToBuffer}) inside
 * {@code ModelFeatureRenderer.prepareModel}, only during the level's own prepare stage. Behaviour is unchanged; with
 * timers off the wrappers only call through.
 */
@Mixin(ModelFeatureRenderer.class)
public class ModelFeatureRendererMixin {
    @WrapOperation(
        method = "prepareModel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V")
    )
    private void mobium$timePose(
        Model<?> model,
        Object state,
        Operation<Void> original,
        @Local(argsOnly = true) ModelFeatureRenderer.Submit<?> submit
    ) {
        long start = Sections.inLevelPrepare ? Sections.begin() : 0L;
        original.call(model, state);
        Sections.end(mobium$isBlockEntity(submit) ? Sections.POSE_BLOCK_ENTITIES : Sections.POSE_ENTITIES, start);
    }

    @WrapOperation(
        method = "prepareModel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"
        )
    )
    private void mobium$timeTessellate(
        Model<?> model,
        PoseStack poseStack,
        VertexConsumer buffer,
        int lightCoords,
        int overlayCoords,
        int color,
        Operation<Void> original,
        @Local(argsOnly = true) ModelFeatureRenderer.Submit<?> submit
    ) {
        long start = Sections.inLevelPrepare ? Sections.begin() : 0L;
        original.call(model, poseStack, buffer, lightCoords, overlayCoords, color);
        Sections.end(
            mobium$isBlockEntity(submit) ? Sections.TESSELLATE_BLOCK_ENTITIES : Sections.TESSELLATE_ENTITIES,
            start
        );
    }

    @Unique
    private static boolean mobium$isBlockEntity(ModelFeatureRenderer.Submit<?> submit) {
        return ((TimedSubmit) (Object) submit).mobium$isBlockEntity();
    }
}
