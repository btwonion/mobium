package dev.nyon.mobium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.nyon.mobium.cache.StaticCache;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/*? if neoforge {*/
/*import net.minecraft.client.renderer.culling.Frustum;
*//*?}*/

/**
 * Static cache (Module 1): while it is active, cached block entities return no render state, so vanilla neither
 * submits nor tessellates them and Mobium draws them from its section buffers instead. Reached by vanilla's extraction
 * loop, by Sodium's replacement of it and by Iris's shadow pass (on NeoForge the deprecated four-argument overload
 * forwards to the five-argument one wrapped here). Fallback: when the cache is off or the block entity is not
 * cacheable this frame ({@code CachePolicy}), the original method runs unchanged.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public class BlockEntityRenderDispatcherMixin {
    @Shadow
    private Vec3 cameraPos;

    /*? if fabric {*/
    @WrapMethod(
        method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;"
    )
    private @Nullable BlockEntityRenderState mobium$cacheBlockEntities(
        BlockEntity blockEntity,
        float partialTicks,
        ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress,
        boolean isGloballyRendered,
        Operation<BlockEntityRenderState> original
    ) {
        if (!StaticCache.active) return original.call(blockEntity, partialTicks, breakProgress, isGloballyRendered);
        return StaticCache.extract(
            (BlockEntityRenderDispatcher) (Object) this,
            blockEntity,
            partialTicks,
            breakProgress,
            isGloballyRendered,
            null,
            this.cameraPos,
            original
        );
    }
    /*?} else {*/
    /*@WrapMethod(
        method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;ZLnet/minecraft/client/renderer/culling/Frustum;)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;"
    )
    private @Nullable BlockEntityRenderState mobium$cacheBlockEntities(
        BlockEntity blockEntity,
        float partialTicks,
        ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress,
        boolean isGloballyRendered,
        @Nullable Frustum frustum,
        Operation<BlockEntityRenderState> original
    ) {
        if (!StaticCache.active) {
            return original.call(blockEntity, partialTicks, breakProgress, isGloballyRendered, frustum);
        }
        return StaticCache.extract(
            (BlockEntityRenderDispatcher) (Object) this,
            blockEntity,
            partialTicks,
            breakProgress,
            isGloballyRendered,
            frustum,
            this.cameraPos,
            original
        );
    }
    *//*?}*/
}
