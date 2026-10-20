package dev.nyon.mobium.mixins;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Static cache (Module 1): reads what an item state draws, so that a cached item can be compared with the state vanilla
 * extracts each frame ({@code ItemFingerprint}). Read-only; no behaviour change.
 */
@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
    @Accessor("displayContext")
    ItemDisplayContext mobium$displayContext();

    @Accessor("activeLayerCount")
    int mobium$activeLayerCount();

    @Accessor("layers")
    ItemStackRenderState.LayerRenderState[] mobium$layers();
}
