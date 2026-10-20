package dev.nyon.mobium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.nyon.mobium.cache.StaticCache;
import dev.nyon.mobium.debug.Sections;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times the level's submit stage (split by entities and block entities) and its prepare stage, and marks both so that
 * model submits and prepares can be attributed; with timers off the wrappers only call through. Also runs the static
 * cache's prepare after the level's prepare stage and draws its cached objects in the solid pass (both no-ops while
 * the cache is off).
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Shadow
    @Final
    private LevelRenderState levelRenderState;

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
            FeatureRenderDispatcher.PreparedFrame frame = original.call(dispatcher, storage);
            Vec3 camera = this.levelRenderState.cameraRenderState.pos;
            StaticCache.prepare(camera.x, camera.y, camera.z);
            return frame;
        } finally {
            Sections.inLevelPrepare = false;
            Sections.endProfiled(Sections.PREPARE, start);
        }
    }

    // Static cache (Module 1): draws the cached objects right after vanilla's solid features, in the same render pass,
    // where vanilla would have drawn them (sign text included: it is opaque and vanilla puts it into the solid phase).
    // Draws nothing while the cache is off.
    @Inject(
        method = "executeSolid",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid(Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
            shift = At.Shift.AFTER
        )
    )
    private void mobium$drawCachedObjects(
        ChunkSectionsToRender chunkSectionsToRender,
        FeatureRenderDispatcher.PreparedFrame featureFrame,
        RenderPass renderPass,
        CallbackInfo ci
    ) {
        long start = Sections.begin();
        StaticCache.draw(renderPass);
        Sections.end(Sections.DRAW, start);
    }
}
