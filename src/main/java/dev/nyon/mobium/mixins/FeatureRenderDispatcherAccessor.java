package dev.nyon.mobium.mixins;

import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRendererMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Static cache (Module 1): finds the feature renderer other mods register for their own submit types (Fabric's
 * renderer API: extended block model submits, rendered by Sodium or Indigo), so a capture can let that renderer write
 * the vertices. Read-only; no behaviour change.
 */
@Mixin(FeatureRenderDispatcher.class)
public interface FeatureRenderDispatcherAccessor {
    @Accessor("featureRenderers")
    FeatureRendererMap mobium$featureRenderers();
}
