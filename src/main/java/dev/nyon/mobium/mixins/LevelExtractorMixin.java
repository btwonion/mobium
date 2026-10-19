package dev.nyon.mobium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.nyon.mobium.debug.Sections;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Times the extract stage: vanilla's entity and block entity culling plus render-state extraction (or Sodium's
 * replacement of the block entity loop, which runs inside the wrapped method). Behaviour is unchanged; with timers
 * off the wrappers only call through.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
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
