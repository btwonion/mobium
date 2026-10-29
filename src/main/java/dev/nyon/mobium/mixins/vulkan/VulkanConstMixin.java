package dev.nyon.mobium.mixins.vulkan;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import dev.nyon.mobium.gpu.vk.VulkanSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Module 2 on Vulkan: its expand pass writes into vanilla's vertex buffers as storage buffers and its check copies
 * them out, usages Renderpearl never asks for ({@code docs/vanilla-notes.md} §18). In sessions where Module 2 can run
 * on Vulkan, vertex buffers also get {@code VK_BUFFER_USAGE_STORAGE_BUFFER_BIT} and
 * {@code VK_BUFFER_USAGE_TRANSFER_SRC_BIT}; otherwise the usage is vanilla's. {@code require = 0}: without the hook
 * {@code VulkanSupport} sees no storage usage and Module 2 stays off on Vulkan.
 */
@Mixin(VulkanConst.class)
public class VulkanConstMixin {
    @ModifyReturnValue(method = "bufferUsageToVk", at = @At("RETURN"), require = 0)
    private static int mobium$storageOnVertexBuffers(int vkUsage, @Local(argsOnly = true) int usage) {
        return VulkanSupport.bufferUsage(usage, vkUsage);
    }
}
