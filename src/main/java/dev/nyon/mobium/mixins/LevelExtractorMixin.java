package dev.nyon.mobium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.nyon.mobium.cache.ChestPrototype;
import dev.nyon.mobium.debug.Sections;
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
 * through. Also clears the chest prototype's cache when the level or the render distance changes.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
    // Chest prototype (M3): a new level, a resource reload or a render distance change drops every cached chest, which
    // are then captured again from vanilla. Nothing changes while the prototype is off.
    @Inject(method = {"setLevel", "allChanged"}, at = @At("RETURN"))
    private void mobium$clearCache(CallbackInfo ci) {
        ChestPrototype.clear();
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
