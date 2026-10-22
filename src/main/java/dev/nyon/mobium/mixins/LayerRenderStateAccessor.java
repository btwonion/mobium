package dev.nyon.mobium.mixins;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Static cache (Module 1): reads one layer of an item state, everything {@code LayerRenderState.submit} uses.
 * Read-only; no behaviour change.
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface LayerRenderStateAccessor {
    @Accessor("quads")
    ItemQuads mobium$quads();

    @Accessor("itemTransform")
    ItemTransform mobium$itemTransform();

    @Accessor("localTransform")
    Matrix4f mobium$localTransform();

    @Accessor("foilType")
    ItemStackRenderState.FoilType mobium$foilType();

    @Accessor("tintLayers")
    @Nullable IntList mobium$tintLayers();

    @Accessor("specialRenderer")
    @Nullable SpecialModelRenderer<Object> mobium$specialRenderer();
}
