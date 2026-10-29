package dev.nyon.mobium.mixins;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the backend behind {@code RenderSystem.getDevice()}, a {@code VulkanDevice} on Vulkan, which Renderpearl keeps
 * private ({@code docs/vanilla-notes.md} §18). Reads the field only; nothing else changes.
 */
@Mixin(FrontendGpuDevice.class)
public interface FrontendGpuDeviceAccessor {
    @Accessor("backend")
    GpuDeviceBackend mobium$backend();
}
