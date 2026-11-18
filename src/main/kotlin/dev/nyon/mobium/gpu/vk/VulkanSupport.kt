package dev.nyon.mobium.gpu.vk

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.backend.vulkan.VulkanConst
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature
import dev.nyon.mobium.Mobium
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.Module
import dev.nyon.mobium.offload.ExpandPass
import org.lwjgl.vulkan.VK10

/**
 * What vertex offload needs from Minecraft's Vulkan backend beyond Renderpearl's API (docs/vanilla-notes.md §18, §23):
 * storage and transfer source usage on vertex buffers, which the expand pass writes and the check copies out, and,
 * where the GPU has them, 64-bit floats in shaders through an optional feature set of Mobium's own ([FLOAT64]);
 * without them the expand pass computes in integers (§26). Three mixins in `mixins/vulkan` provide them, each
 * `require = 0`: when the device or the usage hook does not apply, [available] stays false and vertex offload stays off
 * on Vulkan with a reason instead of the game crashing; without the feature set hook it runs with integers.
 *
 * Vanilla's device and buffers change only in sessions where vertex offload can run on Vulkan: never while an installed
 * mod keeps it on the vanilla path ([heldBack]).
 */
object VulkanSupport {
    /**
     * Why Mobium leaves vanilla's feature sets and buffer usages alone in this session, or null: an installed mod
     * keeps vertex offload on the vanilla path (`Compat.keepsOff`), or the device is created before the mods are
     * detected. Decided once, when the backend first asks for the optional feature sets.
     */
    private val heldBack: String? by lazy {
        if (!Compat.detected) "the device was created before the mods were detected"
        else Compat.keepsOff(Module.VERTEX_OFFLOAD)?.let { "$it is installed" }
    }

    /**
     * `-Dmobium.offload.arithmetic=integer`: the session runs as on a GPU without `shaderFloat64`. Mobium does not
     * offer [FLOAT64], so the device is created without the feature and a shader that needed it would be refused.
     */
    private val integerOnly: Boolean
        get() = ExpandPass.integerOnly

    /**
     * `shaderFloat64`, offered to `VulkanBackend` as an optional feature set: enabled where the GPU has it. Built on
     * first use, which only the Vulkan hooks make.
     */
    val FLOAT64: FeatureSet by lazy {
        FeatureSet("Mobium vertex offload (shaderFloat64)", setOf(), setOf(VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderFloat64")))
    }

    /** The `VulkanDevice` constructor hook ran. */
    private var deviceSeen = false

    /** The device was created with [FLOAT64] (seen by the `VulkanDevice` constructor hook): shaders may use doubles. */
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

    /** Vertex offload's expand pass can run on this Vulkan device, with doubles ([float64]) or with integers. */
    val available: Boolean
        get() = heldBack == null && storageUsage

    /** Whether [available] and with which arithmetic, or why not, for the log. */
    fun describe(): String = when {
        heldBack != null -> "not used ($heldBack)"
        !deviceSeen -> "the device hook did not apply"
        !storageUsage -> "the storage usage hook did not apply"
        float64 -> "available, with 64-bit floats in shaders"
        integerOnly -> "available, with integer arithmetic (-Dmobium.offload.arithmetic=integer)"
        else -> "available, with integer arithmetic (no shaderFloat64: the GPU lacks it, or the feature set hook did not apply)"
    }

    /**
     * `VulkanFeatureSets.optionalFeatureSets()`: adds [FLOAT64] unless vertex offload is [heldBack] or runs
     * [integerOnly].
     */
    @JvmStatic
    fun optionalFeatureSets(sets: Set<FeatureSet>): Set<FeatureSet> {
        if (heldBack != null || integerOnly) return sets
        val extended = HashSet(sets)
        extended += FLOAT64
        return extended
    }

    /**
     * The `VulkanDevice` constructor ends: which feature sets it enabled decides the expand pass's arithmetic, and
     * unless vertex offload is [heldBack] vertex buffers are storage buffers from here on.
     */
    @JvmStatic
    fun onDeviceCreated(enabled: FeatureSet) {
        deviceSeen = true
        float64 = heldBack == null && !integerOnly && enabled.contains(FLOAT64)
        if (heldBack == null) {
            storageVertexBuffers = true
            storageUsage = VulkanConst.bufferUsageToVk(GpuBuffer.USAGE_VERTEX) and VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT != 0
            if (!storageUsage) storageVertexBuffers = false
        }
        Mobium.LOGGER.info("Mobium vertex offload on Vulkan: {}", describe())
    }

    /** `VulkanConst.bufferUsageToVk`'s result [vkUsage] for Renderpearl's [usage]. */
    @JvmStatic
    fun bufferUsage(usage: Int, vkUsage: Int): Int =
        if (storageVertexBuffers && usage and GpuBuffer.USAGE_VERTEX != 0) vkUsage or VERTEX_EXTRA else vkUsage

    private const val VERTEX_EXTRA = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
}
