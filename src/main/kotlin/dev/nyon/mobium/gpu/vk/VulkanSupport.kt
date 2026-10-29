package dev.nyon.mobium.gpu.vk

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.backend.vulkan.VulkanConst
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature
import dev.nyon.mobium.Mobium
import org.lwjgl.vulkan.VK10

/**
 * What Module 2 needs from Minecraft's Vulkan backend beyond Renderpearl's API (docs/vanilla-notes.md §18, §23):
 * 64-bit floats in shaders, through an optional feature set of Mobium's own ([FLOAT64]), and storage and transfer
 * source usage on vertex buffers, which the expand pass writes and the check copies out. Three mixins in `mixins/vulkan` provide them, each `require = 0`: when one
 * does not apply, [available] stays false and Module 2 stays off on Vulkan with a reason instead of the game crashing.
 *
 * Vanilla's buffers change only in sessions where Module 2 can run on Vulkan ([storageVertexBuffers]), and only while
 * Module 2 on Vulkan is being built ([allowed], PLAN.md M7 steps 4 and 5) at all.
 */
object VulkanSupport {
    /** Module 2 on Vulkan before its checks are done (PLAN.md M7 step 5): `-Dmobium.parts.vulkan=true`, dev clients on Vulkan. */
    val allowed: Boolean
        get() = System.getProperty("mobium.parts.vulkan").toBoolean()

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
        get() = allowed && float64 && storageUsage

    /** Why [available] is false, for the log. */
    fun describe(): String = when {
        !allowed -> "not enabled (-Dmobium.parts.vulkan)"
        !float64 -> "no shaderFloat64 (the GPU lacks it, or the feature set hook did not apply)"
        !storageUsage -> "the storage usage hook did not apply"
        else -> "available"
    }

    /** `VulkanFeatureSets.optionalFeatureSets()`: adds [FLOAT64] while Module 2 on Vulkan is [allowed]. */
    @JvmStatic
    fun optionalFeatureSets(sets: Set<FeatureSet>): Set<FeatureSet> {
        if (!allowed) return sets
        val extended = HashSet(sets)
        extended += FLOAT64
        return extended
    }

    /** The `VulkanDevice` constructor ends: which feature sets it enabled decides whether Module 2 can run. */
    @JvmStatic
    fun onDeviceCreated(enabled: FeatureSet) {
        float64 = enabled.contains(FLOAT64)
        if (!allowed || !float64) return
        storageVertexBuffers = true
        storageUsage = VulkanConst.bufferUsageToVk(GpuBuffer.USAGE_VERTEX) and VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT != 0
        if (!storageUsage) storageVertexBuffers = false
        Mobium.LOGGER.info("Mobium part transform on Vulkan: {}", describe())
    }

    /** `VulkanConst.bufferUsageToVk`'s result [vkUsage] for Renderpearl's [usage]. */
    @JvmStatic
    fun bufferUsage(usage: Int, vkUsage: Int): Int =
        if (storageVertexBuffers && usage and GpuBuffer.USAGE_VERTEX != 0) vkUsage or VERTEX_EXTRA else vkUsage

    private const val VERTEX_EXTRA = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
}
