package dev.nyon.mobium.mixins.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.checkpoints.CheckpointExtension;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.nyon.mobium.gpu.vk.VulkanSupport;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads which feature sets the Vulkan device was created with (Renderpearl keeps them private), so that Module 2
 * knows whether 64-bit floats are enabled. Reads only; nothing changes. {@code require = 0}: without the hook
 * {@code VulkanSupport} sees no 64-bit floats and Module 2 stays off on Vulkan.
 */
@Mixin(VulkanDevice.class)
public class VulkanDeviceMixin {
    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void mobium$deviceCreated(
        VulkanInstance instance,
        VulkanPhysicalDevice physicalDevice,
        FeatureSet enabledFeatureSet,
        VkDevice vkDevice,
        long vma,
        CheckpointExtension checkpointExtension,
        CallbackInfo ci
    ) {
        VulkanSupport.onDeviceCreated(enabledFeatureSet);
    }
}
