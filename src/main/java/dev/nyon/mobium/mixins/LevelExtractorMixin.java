package dev.nyon.mobium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.nyon.mobium.cache.StaticCache;
import dev.nyon.mobium.debug.Sections;
import dev.nyon.mobium.parts.PartTransform;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times the extract stage: vanilla's entity and block entity culling plus render-state extraction (or Sodium's
 * replacement of the block entity loop, which runs inside the wrapped method); with timers off the wrappers only call
 * through. Also clears the static cache and Module 2's meshes when the level, the render distance or Iris's pipeline
 * changes.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
    // Static cache (Module 1): a new level, a resource reload or a render distance change drops every cached object,
    // which is then captured again from vanilla; Module 2 writes its cube meshes again (a reload rebuilds the models).
    // Nothing changes while the modules are off.
    @Inject(method = {"setLevel", "allChanged"}, at = @At("RETURN"))
    private void mobium$clearCache(CallbackInfo ci) {
        StaticCache.clear();
        PartTransform.clearMeshes();
    }

    // Static cache (Module 1): checks once per frame, before anything is extracted, whether Iris switched its pipeline
    // (and drops the cache then). Nothing changes while the cache is off.
    @Inject(method = "extract", at = @At("HEAD"))
    private void mobium$beginFrame(DeltaTracker deltaTracker, Camera camera, float worldPartialTicks, CallbackInfo ci) {
        StaticCache.beginFrame();
    }

    @WrapMethod(method = "extractVisibleEntities")
    private void mobium$timeExtractEntities(
        Camera camera,
        Frustum frustum,
        DeltaTracker deltaTracker,
        LevelRenderState output,
        Operation<Void> original
    ) {
        long start = Sections.beginProfiled(Sections.EXTRACT_ENTITIES);
        try {
            original.call(camera, frustum, deltaTracker, output);
        } finally {
            Sections.endProfiled(Sections.EXTRACT_ENTITIES, start);
        }
    }

    @WrapMethod(method = "extractVisibleBlockEntities")
    private void mobium$timeExtractBlockEntities(
        Camera camera,
        float deltaPartialTick,
        LevelRenderState levelRenderState,
        Operation<Void> original
    ) {
        long start = Sections.beginProfiled(Sections.EXTRACT_BLOCK_ENTITIES);
        try {
            original.call(camera, deltaPartialTick, levelRenderState);
        } finally {
            Sections.endProfiled(Sections.EXTRACT_BLOCK_ENTITIES, start);
        }
    }
}
