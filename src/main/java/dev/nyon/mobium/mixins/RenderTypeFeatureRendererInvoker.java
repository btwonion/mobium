package dev.nyon.mobium.mixins;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Lets the cache run a feature renderer's {@code buildGroup} (for models: vanilla's {@code prepareModel} per submit)
 * outside the frame, while {@link RenderTypeFeatureRendererMixin} redirects its vertices, and Module 2's items ask for
 * a render type's builder as {@code ItemFeatureRenderer} does. No behaviour change.
 */
@Mixin(RenderTypeFeatureRenderer.class)
public interface RenderTypeFeatureRendererInvoker {
    @Invoker("buildGroup")
    void mobium$buildGroup(@Nullable FeatureFrameContext context, List<?> submits);

    @Invoker("getVertexBuilder")
    VertexConsumer mobium$getVertexBuilder(RenderType renderType);
}
