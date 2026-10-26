package dev.nyon.mobium.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.nyon.mobium.cache.EntityStateSource;
import dev.nyon.mobium.cache.StaticCache;
import dev.nyon.mobium.parts.PartTransform;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Static cache (Module 1) for entities. Entities are always extracted; for an entity drawn from the cache this frame
 * the renderer's own submit is skipped, so vanilla neither submits nor tessellates it, while the dispatcher still
 * submits its flame and shadow. Fallback: while the cache is off, or the entity is not cacheable this frame
 * ({@code EntityPolicy}), the renderer's submit runs unchanged. Also marks the model submits of entity types excluded
 * from Module 2.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    // Render states carry no reference to their entity; the cache keys its entries by entity. Only the states of types
    // the cache may take get one, so other holders of render states keep no entity alive. No behaviour change.
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void mobium$linkEntity(Entity entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
        EntityRenderState state = cir.getReturnValue();
        if (state != null && StaticCache.linksEntity(entity)) ((EntityStateSource) state).mobium$setEntity(entity);
    }

    @WrapOperation(
        method = "submit",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
        )
    )
    private void mobium$cacheEntities(
        EntityRenderer<?, ?> renderer,
        EntityRenderState state,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        CameraRenderState camera,
        Operation<Void> original
    ) {
        if (StaticCache.active && StaticCache.entity((EntityRenderDispatcher) (Object) this, state)) return;
        // Module 2: the model submits of excluded entity types are marked and keep vanilla's CPU path.
        PartTransform.submittingExcluded = PartTransform.excludes(state.entityType);
        try {
            original.call(renderer, state, poseStack, submitNodeCollector, camera);
        } finally {
            PartTransform.submittingExcluded = false;
        }
    }
}
