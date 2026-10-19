package dev.nyon.mobium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.nyon.mobium.debug.Sections;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Times the level's submit stage (split by entities and block entities) and its prepare stage, and marks both so that
 * model submits and prepares can be attributed. Behaviour is unchanged; with timers off the wrappers only call through.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @WrapMethod(method = "submitEntities")
    private void mobium$timeSubmitEntities(
        PoseStack poseStack,
        LevelRenderState levelRenderState,
        SubmitNodeCollector output,
        Operation<Void> original
    ) {
        Sections.entities = levelRenderState.entityRenderStates.size();
        long start = Sections.beginProfiled(Sections.SUBMIT_ENTITIES);
        try {
            original.call(poseStack, levelRenderState, output);
        } finally {
            Sections.endProfiled(Sections.SUBMIT_ENTITIES, start);
        }
    }

    @WrapMethod(method = "submitBlockEntities")
    private void mobium$timeSubmitBlockEntities(
        PoseStack poseStack,
        LevelRenderState levelRenderState,
        SubmitNodeCollector submitNodeCollector,
        Operation<Void> original
    ) {
        Sections.blockEntities = levelRenderState.blockEntityRenderStates.size();
        long start = Sections.beginProfiled(Sections.SUBMIT_BLOCK_ENTITIES);
        Sections.submittingBlockEntities = true;
        try {
            original.call(poseStack, levelRenderState, submitNodeCollector);
        } finally {
            Sections.submittingBlockEntities = false;
            Sections.endProfiled(Sections.SUBMIT_BLOCK_ENTITIES, start);
        }
    }

    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
        )
    )
    private FeatureRenderDispatcher.PreparedFrame mobium$timePrepare(
        FeatureRenderDispatcher dispatcher,
        SubmitNodeStorage storage,
        Operation<FeatureRenderDispatcher.PreparedFrame> original
    ) {
        long start = Sections.beginProfiled(Sections.PREPARE);
        Sections.inLevelPrepare = true;
        try {
            return original.call(dispatcher, storage);
        } finally {
            Sections.inLevelPrepare = false;
            Sections.endProfiled(Sections.PREPARE, start);
        }
    }
}
