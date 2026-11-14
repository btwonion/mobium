package dev.nyon.mobium.mixins.vulkan;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.nyon.mobium.gpu.vk.VulkanSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;

/**
 * Module 2 on Vulkan uses 64-bit floats in shaders where the GPU has them, which Renderpearl does not enable: offers
 * an optional feature set with {@code shaderFloat64}, which {@code VulkanBackend} enables where the GPU has it, like
 * its own optional sets ({@code docs/vanilla-notes.md} §18). Not while an installed mod keeps Module 2 on the vanilla
 * path ({@code VulkanSupport}); then the sets are vanilla's.
 * {@code require = 0}: without the hook the feature stays off and Module 2's expand pass computes in integers, as on
 * a GPU without the feature ({@code docs/vanilla-notes.md} §26).
 */
@Mixin(VulkanFeatureSets.class)
public class VulkanFeatureSetsMixin {
    @ModifyReturnValue(method = "optionalFeatureSets", at = @At("RETURN"), require = 0)
    private static Set<FeatureSet> mobium$offerFloat64(Set<FeatureSet> sets) {
        return VulkanSupport.optionalFeatureSets(sets);
    }
}
