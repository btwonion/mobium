package dev.nyon.mobium.gpu.vk

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.backend.vulkan.VulkanConst
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature
import dev.nyon.mobium.Mobium
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.Module
import org.lwjgl.vulkan.VK10

/**
 * What Module 2 needs from Minecraft's Vulkan backend beyond Renderpearl's API (docs/vanilla-notes.md §18, §23):
 * 64-bit floats in shaders, through an optional feature set of Mobium's own ([FLOAT64]), and storage and transfer
 * source usage on vertex buffers, which the expand pass writes and the check copies out. Three mixins in `mixins/vulkan` provide them, each `require = 0`: when one
 * does not apply, [available] stays false and Module 2 stays off on Vulkan with a reason instead of the game crashing.
 *
 * Vanilla's device and buffers change only in sessions where Module 2 can run on Vulkan: never while an installed mod
 * keeps it on the vanilla path ([heldBack]), and the buffers only on a device with 64-bit floats
 * ([storageVertexBuffers]).
 */
object VulkanSupport {
    /**
     * Why Mobium leaves vanilla's feature sets and buffer usages alone in this session, or null: an installed mod
     * keeps Module 2 on the vanilla path (`Compat.keepsOff`), or the device is created before the mods are detected.
     * Decided once, when the backend first asks for the optional feature sets.
     */
    private val heldBack: String? by lazy {
        if (!Compat.detected) "the device was created before the mods were detected"
        else Compat.keepsOff(Module.PART_TRANSFORM)?.let { "$it is installed" }
    }

    /**
     * `shaderFloat64`, offered to `VulkanBackend` as an optional feature set: enabled where the GPU has it. Built on
     * first use, which only the Vulkan hooks make.
     */
    val FLOAT64: FeatureSet by lazy {
        FeatureSet("Mobium part transform (shaderFloat64)", setOf(), setOf(VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderFloat64")))
    }

    /** The device was created with [FLOAT64] (seen by the `VulkanDevice` constructor hook). */
    var float64 = false
        private set

    /** `VulkanConst.bufferUsageToVk` adds the storage usage to vertex buffers (checked once the device exists). */
    var storageUsage = false
        private set

    /**
     * Whether vertex buffers get `VK_BUFFER_USAGE_STORAGE_BUFFER_BIT` (the expand pass writes them) and
     * `VK_BUFFER_USAGE_TRANSFER_SRC_BIT` (the check copies them out); read by the `VulkanConst` hook.
     */
    @JvmStatic
    var storageVertexBuffers = false
        private set

    /** Module 2's expand pass can run on this Vulkan device. */
    val available: Boolean
        get() = heldBack == null && float64 && storageUsage

    /** Why [available] is false, for the log. */
    fun describe(): String = when {
        heldBack != null -> "not used ($heldBack)"
        !float64 -> "no shaderFloat64 (the GPU lacks it, or the feature set hook did not apply)"
        !storageUsage -> "the storage usage hook did not apply"
        else -> "available"
    }

    /** `VulkanFeatureSets.optionalFeatureSets()`: adds [FLOAT64] unless Module 2 is [heldBack]. */
    @JvmStatic
    fun optionalFeatureSets(sets: Set<FeatureSet>): Set<FeatureSet> {
        if (heldBack != null) return sets
        val extended = HashSet(sets)
        extended += FLOAT64
        return extended
    }

    /** The `VulkanDevice` constructor ends: which feature sets it enabled decides whether Module 2 can run. */
    @JvmStatic
    fun onDeviceCreated(enabled: FeatureSet) {
        float64 = enabled.contains(FLOAT64)
        if (heldBack == null && float64) {
            storageVertexBuffers = true
            storageUsage = VulkanConst.bufferUsageToVk(GpuBuffer.USAGE_VERTEX) and VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT != 0
            if (!storageUsage) storageVertexBuffers = false
        }
        Mobium.LOGGER.info("Mobium part transform on Vulkan: {}", describe())
    }

    /** `VulkanConst.bufferUsageToVk`'s result [vkUsage] for Renderpearl's [usage]. */
    @JvmStatic
    fun bufferUsage(usage: Int, vkUsage: Int): Int =
        if (storageVertexBuffers && usage and GpuBuffer.USAGE_VERTEX != 0) vkUsage or VERTEX_EXTRA else vkUsage

    private const val VERTEX_EXTRA = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
}
