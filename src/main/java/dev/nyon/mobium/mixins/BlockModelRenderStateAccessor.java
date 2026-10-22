package dev.nyon.mobium.mixins;

import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Static cache (Module 1): reads what a block model state draws (item frames' frame, block displays), so that a cached
 * entity can be compared with the state vanilla extracts each frame ({@code BlockModelFingerprint}). Read-only; no
 * behaviour change.
 */
@Mixin(BlockModelRenderState.class)
public interface BlockModelRenderStateAccessor {
    @Accessor("modelParts")
    @Nullable List<BlockStateModelPart> mobium$modelParts();

    @Accessor("transformation")
    @Nullable Matrix4fc mobium$transformation();

    @Accessor("renderType")
    @Nullable RenderType mobium$renderType();

    @Accessor("specialRenderer")
    @Nullable SpecialModelRenderer<?> mobium$specialRenderer();

    @Accessor("tintLayers")
    @Nullable IntList mobium$tintLayers();
}
