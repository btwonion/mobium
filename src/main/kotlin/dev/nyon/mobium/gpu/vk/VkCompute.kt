package dev.nyon.mobium.gpu.vk

import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.util.vma.Vma
import org.lwjgl.util.vma.VmaAllocationCreateInfo
import org.lwjgl.util.vma.VmaAllocationInfo
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkComputePipelineCreateInfo
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo
import org.lwjgl.vulkan.VkDescriptorPoolSize
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkMemoryBarrier
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo
import org.lwjgl.vulkan.VkPushConstantRange
import org.lwjgl.vulkan.VkShaderModuleCreateInfo
import java.nio.ByteBuffer

/**
 * Raw Vulkan for Module 2's expand pass, which Renderpearl has no API for (docs/vanilla-notes.md §18, §23): GLSL to
 * SPIR-V with shaderc as Renderpearl's own `GlslCompiler` does, buffers from Renderpearl's VMA allocator, a compute
 * pipeline whose descriptor set holds storage buffers only, descriptor pools and barriers. The Vulkan counterpart of
 * `gpu/gl/GlCompute`. Render thread only.
 */
object VkCompute {
    /** A buffer of Mobium's own; [mapped] is the persistent mapping of a host-visible one, else 0. */
    class Buffer(@JvmField val handle: Long, @JvmField val allocation: Long, @JvmField val size: Long, @JvmField val mapped: Long)

    /** A compute pipeline with one descriptor set of storage buffers and a push constant range. */
    class Pipeline(@JvmField val pipeline: Long, @JvmField val layout: Long, @JvmField val setLayout: Long, @JvmField val module: Long)

    /** For [Buffer.mapped]: the CPU writes it front to back (uploads) or reads it (read-backs). */
    const val DEVICE = 0
    const val UPLOAD = 1
    const val READBACK = 2

    /**
     * Compiles compute shader GLSL to SPIR-V for Vulkan 1.2 without optimisation, with each of [defines] defined as 1;
     * throws with shaderc's message if that fails. Returns a native buffer the caller frees ([MemoryUtil.memFree]).
     */
    fun compile(name: String, source: String, defines: List<String>): ByteBuffer {
        val compiler = Shaderc.shaderc_compiler_initialize()
        check(compiler != 0L) { "shaderc is not available" }
        val options = Shaderc.shaderc_compile_options_initialize()
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2)
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_zero)
            for (define in defines) Shaderc.shaderc_compile_options_add_macro_definition(options, define, "1")
            val result = Shaderc.shaderc_compile_into_spv(compiler, source, Shaderc.shaderc_compute_shader, name, "main", options)
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                    throw IllegalStateException("$name does not compile for Vulkan: ${Shaderc.shaderc_result_get_error_message(result)}")
                }
                val bytes = checkNotNull(Shaderc.shaderc_result_get_bytes(result)) { "$name: shaderc returned no SPIR-V" }
                val copy = MemoryUtil.memAlloc(bytes.remaining())
                copy.put(bytes).flip()
                return copy
            } finally {
                Shaderc.shaderc_result_release(result)
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options)
            Shaderc.shaderc_compiler_release(compiler)
        }
    }

    /** A buffer of [size] bytes with Vulkan [usage]; [access] is [DEVICE], [UPLOAD] or [READBACK]. */
    fun createBuffer(vma: Long, size: Long, usage: Int, access: Int): Buffer = MemoryStack.stackPush().use { stack ->
        val bufferInfo = VkBufferCreateInfo.calloc(stack).`sType$Default`()
            .size(size)
            .usage(usage)
            .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
        val allocationInfo = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO)
        when (access) {
            UPLOAD -> allocationInfo.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT or Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT)
            READBACK -> allocationInfo.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT or Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT)
        }
        val buffer = stack.mallocLong(1)
        val allocation: PointerBuffer = stack.mallocPointer(1)
        val info = VmaAllocationInfo.calloc(stack)
        vkCheck(Vma.vmaCreateBuffer(vma, bufferInfo, allocationInfo, buffer, allocation, info), "vmaCreateBuffer")
        Buffer(buffer.get(0), allocation.get(0), size, if (access == DEVICE) 0L else info.pMappedData())
    }

    fun destroyBuffer(vma: Long, buffer: Buffer) {
        Vma.vmaDestroyBuffer(vma, buffer.handle, buffer.allocation)
    }

    /** Makes the CPU's writes to [buffer] in [offset, offset + bytes) visible to the device (no-op on coherent memory). */
    fun flush(vma: Long, buffer: Buffer, offset: Long, bytes: Long) {
        Vma.vmaFlushAllocation(vma, buffer.allocation, offset, bytes)
    }

    /** Makes the device's writes to [buffer] visible to the CPU (no-op on coherent memory). */
    fun invalidate(vma: Long, buffer: Buffer, offset: Long, bytes: Long) {
        Vma.vmaInvalidateAllocation(vma, buffer.allocation, offset, bytes)
    }

    /** A compute pipeline for [spirv] with [storageBuffers] storage buffers at bindings 0.. and [pushConstantBytes]. */
    fun createPipeline(device: VkDevice, spirv: ByteBuffer, storageBuffers: Int, pushConstantBytes: Int): Pipeline = MemoryStack.stackPush().use { stack ->
        val handle = stack.mallocLong(1)
        val moduleInfo = VkShaderModuleCreateInfo.calloc(stack).`sType$Default`().pCode(spirv)
        vkCheck(VK10.vkCreateShaderModule(device, moduleInfo, null, handle), "vkCreateShaderModule")
        val module = handle.get(0)

        val bindings = VkDescriptorSetLayoutBinding.calloc(storageBuffers, stack)
        for (i in 0 until storageBuffers) {
            bindings.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
        }
        val setLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).`sType$Default`().pBindings(bindings)
        vkCheck(VK10.vkCreateDescriptorSetLayout(device, setLayoutInfo, null, handle), "vkCreateDescriptorSetLayout")
        val setLayout = handle.get(0)

        val pushRange = VkPushConstantRange.calloc(1, stack).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushConstantBytes)
        val layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).`sType$Default`()
            .pSetLayouts(stack.longs(setLayout))
            .pPushConstantRanges(pushRange)
        vkCheck(VK10.vkCreatePipelineLayout(device, layoutInfo, null, handle), "vkCreatePipelineLayout")
        val layout = handle.get(0)

        val pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
        pipelineInfo.get(0).`sType$Default`().layout(layout).stage().`sType$Default`()
            .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
            .module(module)
            .pName(stack.UTF8("main"))
        vkCheck(VK10.vkCreateComputePipelines(device, VK10.VK_NULL_HANDLE, pipelineInfo, null, handle), "vkCreateComputePipelines")
        Pipeline(handle.get(0), layout, setLayout, module)
    }

    fun destroyPipeline(device: VkDevice, pipeline: Pipeline) {
        VK10.vkDestroyPipeline(device, pipeline.pipeline, null)
        VK10.vkDestroyPipelineLayout(device, pipeline.layout, null)
        VK10.vkDestroyDescriptorSetLayout(device, pipeline.setLayout, null)
        VK10.vkDestroyShaderModule(device, pipeline.module, null)
    }

    /** A descriptor pool for [sets] sets of storage buffers only, [perSet] each. */
    fun createDescriptorPool(device: VkDevice, sets: Int, perSet: Int): Long = MemoryStack.stackPush().use { stack ->
        val sizes = VkDescriptorPoolSize.calloc(1, stack)
        sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(sets * perSet)
        val info = VkDescriptorPoolCreateInfo.calloc(stack).`sType$Default`().maxSets(sets).pPoolSizes(sizes)
        val handle = stack.mallocLong(1)
        vkCheck(VK10.vkCreateDescriptorPool(device, info, null, handle), "vkCreateDescriptorPool")
        handle.get(0)
    }

    /** A set of [setLayout] from [pool]; throws when the pool is exhausted. */
    fun allocateSet(device: VkDevice, pool: Long, setLayout: Long, stack: MemoryStack): Long {
        val info = VkDescriptorSetAllocateInfo.calloc(stack).`sType$Default`()
            .descriptorPool(pool)
            .pSetLayouts(stack.longs(setLayout))
        val handle = stack.mallocLong(1)
        vkCheck(VK10.vkAllocateDescriptorSets(device, info, handle), "vkAllocateDescriptorSets")
        return handle.get(0)
    }

    /** A global memory barrier from [srcStage]'s [srcAccess] to [dstStage]'s [dstAccess]. */
    fun barrier(commandBuffer: VkCommandBuffer, srcStage: Int, srcAccess: Int, dstStage: Int, dstAccess: Int, stack: MemoryStack) {
        val barrier = VkMemoryBarrier.calloc(1, stack).`sType$Default`().srcAccessMask(srcAccess).dstAccessMask(dstAccess)
        VK10.vkCmdPipelineBarrier(commandBuffer, srcStage, dstStage, 0, barrier, null, null)
    }

    /** Throws when a Vulkan or VMA [call] did not return `VK_SUCCESS`. */
    fun vkCheck(result: Int, call: String) {
        if (result != VK10.VK_SUCCESS) throw IllegalStateException("$call failed: $result")
    }
}
