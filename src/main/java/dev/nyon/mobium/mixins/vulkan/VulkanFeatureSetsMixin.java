package dev.nyon.mobium.mixins.vulkan;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.nyon.mobium.gpu.vk.VulkanSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;

/**
 * Module 2 on Vulkan needs 64-bit floats in shaders, which Renderpearl does not enable: offers an optional feature set
 * with {@code shaderFloat64}, which {@code VulkanBackend} enables where the GPU has it, like its own optional sets
 * ({@code docs/vanilla-notes.md} §18). Only while Module 2 on Vulkan is allowed; otherwise the sets are vanilla's.
 * {@code require = 0}: without the hook the feature stays off and so does Module 2 on Vulkan.
 */
@Mixin(VulkanFeatureSets.class)
public class VulkanFeatureSetsMixin {
    @ModifyReturnValue(method = "optionalFeatureSets", at = @At("RETURN"), require = 0)
    private static Set<FeatureSet> mobium$offerFloat64(Set<FeatureSet> sets) {
        return VulkanSupport.optionalFeatureSets(sets);
    }
}
