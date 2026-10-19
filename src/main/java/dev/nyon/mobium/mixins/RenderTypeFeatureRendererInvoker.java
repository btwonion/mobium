package dev.nyon.mobium.mixins;

import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Lets the cache run a feature renderer's {@code buildGroup} (for models: vanilla's {@code prepareModel} per submit)
 * outside the frame, while {@link RenderTypeFeatureRendererMixin} redirects its vertices. No behaviour change.
 */
@Mixin(RenderTypeFeatureRenderer.class)
public interface RenderTypeFeatureRendererInvoker {
    @Invoker("buildGroup")
    void mobium$buildGroup(@Nullable FeatureFrameContext context, List<?> submits);
}
