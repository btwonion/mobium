package dev.nyon.mobium.parts

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.commands.GpuFence
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder
import com.mojang.renderpearl.backend.vulkan.VulkanDevice
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer
import dev.nyon.mobium.Mobium
import dev.nyon.mobium.debug.ExpandProfile
import dev.nyon.mobium.gpu.RingAllocator
import dev.nyon.mobium.gpu.vk.VkCompute
import dev.nyon.mobium.mixins.FrontendGpuDeviceAccessor
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK11
import org.lwjgl.vulkan.VK12
import org.lwjgl.vulkan.VkBufferCopy
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkDescriptorBufferInfo
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Properties
import org.lwjgl.vulkan.VkWriteDescriptorSet

/**
 * Module 2's expand pass on Vulkan (docs/vanilla-notes.md §23): the same `expand_parts.comp`, compiled to SPIR-V,
 * as a compute dispatch in a command buffer of Mobium's own that `VulkanCommandEncoder.execute` appends to the
 * frame's submission right after `StagedVertexBuffer.upload()`, so that it runs after the upload's copy and before
 * the first draw. The barrier at its end makes the vertices visible to everything after it.
 *
 * Memory: per frame in flight ([SLOTS]) a persistently mapped upload buffer, into which the frame's records and new
 * mesh quads are copied with `memCopy` (no `ByteBuffer` or buffer slice per upload; LWJGL's struct wrappers for the
 * commands are the only objects per dispatch), and a descriptor pool; a slot is reused once its fence says the GPU
 * is done with it. The meshes live in a device-local buffer that grows by doubling. Buffers that are replaced go away
 * once the GPU is done with them ([retire]). Render thread only.
 */
object VulkanExpand {
    private const val SHADER = "/assets/mobium/shaders/expand_parts.comp"
    private const val SLOTS = RingAllocator.FRAMES_IN_FLIGHT
    private const val LOCAL_SIZE = 64
    private const val PUSH_WORDS = 6
    private const val SETS_PER_FRAME = 64
    private const val INITIAL_UPLOAD_BYTES = 1L shl 20
    private const val INITIAL_MESH_BYTES = 1L shl 20
    private const val MESH_QUAD_BYTES = MeshPool.QUAD_WORDS * 4L
    private const val STORAGE = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
    private const val ALL_MEMORY = VK10.VK_ACCESS_MEMORY_READ_BIT or VK10.VK_ACCESS_MEMORY_WRITE_BIT

    private var device: VulkanDevice? = null
    private var pipeline: VkCompute.Pipeline? = null
    private var maxGroups = 65535
    private var alignment = 256L

    /** The float execution modes the shader was compiled with, for the log. */
    var floatModes = ""
        private set

    private val upload = arrayOfNulls<VkCompute.Buffer>(SLOTS)
    private val uploadUsed = LongArray(SLOTS)
    private val pools = LongArray(SLOTS)
    private val slotFences = arrayOfNulls<GpuFence>(SLOTS)
    private var frame = 0L
    private var slotFrame = -1L
    private var slot = 0
    private var mesh: VkCompute.Buffer? = null

    private class Retired(val buffer: VkCompute.Buffer, val fence: GpuFence)

    private val retired = ArrayList<Retired>()

    /** GPU memory of the pass's own buffers, for F3. */
    val bytesAllocated: Long
        get() = (mesh?.size ?: 0L) + upload.sumOf { it?.size ?: 0L }

    /** Expands [batch] into [vertexBuffer], which `upload()` has just filled. */
    fun dispatch(batch: PartBatch, vertexBuffer: VulkanGpuBuffer) {
        val profiling = ExpandProfile.enabled
        var lap = if (profiling) System.nanoTime() else 0L
        val device = device()
        val pipeline = pipeline(device)
        val vma = device.vma()
        val encoder = device.createCommandEncoder()
        beginSlot(device)

        val cubeBytes = batch.cubeCount.toLong() * PartBatch.CUBE_BYTES
        val partBytes = batch.partCount.toLong() * PartBatch.PART_BYTES
        val slotBytes = batch.draws.size * 4L
        val frameBytes = cubeBytes + partBytes + slotBytes
        val pendingQuads = MeshPool.quadCount - MeshPool.uploadedQuads
        val meshBytes = pendingQuads * MESH_QUAD_BYTES
        // One reservation: a second one could grow the buffer and leave the first in the old one.
        val meshesFrom = (frameBytes + 3) / 4 * 4
        val framesAt = reserve(encoder, vma, meshesFrom + meshBytes)
        val meshesAt = framesAt + meshesFrom
        val ring = upload[slot]!!
        MemoryUtil.memCopy(batch.cubesAddress, ring.mapped + framesAt, cubeBytes)
        MemoryUtil.memCopy(batch.partsAddress, ring.mapped + framesAt + cubeBytes, partBytes)
        MemoryUtil.memCopy(batch.slotsAddress, ring.mapped + framesAt + cubeBytes + partBytes, slotBytes)
        if (meshBytes > 0) MemoryUtil.memCopy(MeshPool.pendingAddress, ring.mapped + meshesAt, meshBytes)
        VkCompute.flush(vma, ring, 0, uploadUsed[slot])
        if (profiling) {
            lap = ExpandProfile.lap(ExpandProfile.RECORDS, lap)
            ExpandProfile.passes++
        }

        MemoryStack.stackPush().use { stack ->
            val commands = encoder.allocateAndBeginTransientCommandBuffer()
            val meshes = meshBuffer(commands, encoder, vma, MeshPool.quadCount * MESH_QUAD_BYTES, stack)
            if (meshBytes > 0) {
                val region = VkBufferCopy.calloc(1, stack).srcOffset(meshesAt).dstOffset(MeshPool.uploadedQuads * MESH_QUAD_BYTES).size(meshBytes)
                VK10.vkCmdCopyBuffer(commands, ring.handle, meshes.handle, region)
                MeshPool.uploaded()
            }
            VkCompute.barrier(
                commands, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_READ_BIT, stack
            )

            val set = VkCompute.allocateSet(device.vkDevice(), pools[slot], pipeline.setLayout, stack)
            val infos = VkDescriptorBufferInfo.calloc(3, stack)
            infos.get(0).buffer(meshes.handle).offset(0).range(VK10.VK_WHOLE_SIZE)
            infos.get(1).buffer(ring.handle).offset(framesAt).range(frameBytes)
            infos.get(2).buffer(vertexBuffer.vkBuffer()).offset(0).range(VK10.VK_WHOLE_SIZE)
            val writes = VkWriteDescriptorSet.calloc(3, stack)
            for (binding in 0 until 3) {
                writes.get(binding).`sType$Default`().dstSet(set).dstBinding(binding).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(VkDescriptorBufferInfo.create(infos.get(binding).address(), 1))
            }
            VK10.vkUpdateDescriptorSets(device.vkDevice(), writes, null)
            VK10.vkCmdBindPipeline(commands, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.pipeline)
            VK10.vkCmdBindDescriptorSets(commands, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.layout, 0, stack.longs(set), null)
            if (profiling) lap = ExpandProfile.lap(ExpandProfile.STATE, lap)

            val push = stack.mallocInt(PUSH_WORDS)
            var quadBase = 0
            while (quadBase < batch.quadCount) {
                val groups = minOf((batch.quadCount - quadBase + LOCAL_SIZE - 1) / LOCAL_SIZE, maxGroups)
                push.put(0, batch.quadCount).put(1, batch.cubeCount).put(2, 0)
                    .put(3, (cubeBytes / 4).toInt()).put(4, ((cubeBytes + partBytes) / 4).toInt()).put(5, quadBase)
                VK10.vkCmdPushConstants(commands, pipeline.layout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push)
                VK10.vkCmdDispatch(commands, groups, 1, 1)
                quadBase += groups * LOCAL_SIZE
            }
            if (profiling) lap = ExpandProfile.lap(ExpandProfile.RUN, lap)
            VkCompute.barrier(
                commands, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, ALL_MEMORY, stack
            )
            VkCompute.vkCheck(VK10.vkEndCommandBuffer(commands), "vkEndCommandBuffer")
            if (profiling) ExpandProfile.passStart()
            encoder.execute(commands)
            fenceSlot(encoder)
            if (profiling) {
                ExpandProfile.passEnd()
                ExpandProfile.lap(ExpandProfile.BARRIER, lap)
            }
        }
    }

    /**
     * After a failure: zeroes [count] ranges of [vertexBuffer] (offsets and lengths in bytes from [starts] and [ends],
     * multiples of 4), so that this one frame draws nothing for the reserved vertices.
     */
    fun blank(vertexBuffer: VulkanGpuBuffer, starts: LongArray, ends: LongArray, count: Int) {
        if (count == 0) return
        val encoder = device().createCommandEncoder()
        MemoryStack.stackPush().use { stack ->
            val commands = encoder.allocateAndBeginTransientCommandBuffer()
            for (i in 0 until count) VK10.vkCmdFillBuffer(commands, vertexBuffer.vkBuffer(), starts[i], ends[i] - starts[i], 0)
            VkCompute.barrier(commands, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, ALL_MEMORY, stack)
            VkCompute.vkCheck(VK10.vkEndCommandBuffer(commands), "vkEndCommandBuffer")
            encoder.execute(commands)
        }
    }

    /**
     * A read-back of [span] bytes of a vertex buffer at [start], for [PartValidation]: the bytes as the expand pass left
     * them and, when [repairs] is above 0, the same span again after the CPU's bytes were written over the compared
     * parts. Valid to read once [fence] has signalled; then [release] it.
     */
    class Readback(val buffer: VkCompute.Buffer, val span: Long, val fence: GpuFence) {
        /** Address of the first (before the repairs) or second copy. */
        fun address(afterRepairs: Boolean): Long = buffer.mapped + if (afterRepairs) span else 0L

        fun read() = VkCompute.invalidate(device().vma(), buffer, 0, buffer.size)
    }

    /**
     * Copies [span] bytes of [vertexBuffer] from [start] into a new read-back; with [repairs] > 0 then writes the bytes
     * at [addresses] ([sizes] bytes each) to [offsets] of the vertex buffer and copies the span again. Recorded after
     * the expand pass, before vanilla's draws, so that the repaired parts are drawn with the CPU's bytes this frame.
     */
    fun readBack(
        vertexBuffer: VulkanGpuBuffer, start: Long, span: Long,
        offsets: LongArray, addresses: LongArray, sizes: LongArray, repairs: Int
    ): Readback {
        val device = device()
        val vma = device.vma()
        val encoder = device.createCommandEncoder()
        beginSlot(device)
        val target = VkCompute.createBuffer(vma, if (repairs > 0) span * 2 else span, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, VkCompute.READBACK)
        // The CPU's bytes for the repairs, in one reservation (a second could grow the buffer away from the first).
        val at = LongArray(repairs)
        var ring: VkCompute.Buffer? = null
        if (repairs > 0) {
            var total = 0L
            for (i in 0 until repairs) total += sizes[i]
            var next = reserve(encoder, vma, total)
            val buffer = upload[slot]!!
            for (i in 0 until repairs) {
                at[i] = next
                MemoryUtil.memCopy(addresses[i], buffer.mapped + next, sizes[i])
                next += sizes[i]
            }
            VkCompute.flush(vma, buffer, 0, uploadUsed[slot])
            ring = buffer
        }
        MemoryStack.stackPush().use { stack ->
            val commands = encoder.allocateAndBeginTransientCommandBuffer()
            val whole = VkBufferCopy.calloc(1, stack).srcOffset(start).dstOffset(0).size(span)
            VK10.vkCmdCopyBuffer(commands, vertexBuffer.vkBuffer(), target.handle, whole)
            if (ring != null) {
                VkCompute.barrier(commands, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_READ_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT, stack)
                val region = VkBufferCopy.calloc(1, stack)
                for (i in 0 until repairs) {
                    region.srcOffset(at[i]).dstOffset(offsets[i]).size(sizes[i])
                    VK10.vkCmdCopyBuffer(commands, ring.handle, vertexBuffer.vkBuffer(), region)
                }
                VkCompute.barrier(commands, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_READ_BIT, stack)
                whole.dstOffset(span)
                VK10.vkCmdCopyBuffer(commands, vertexBuffer.vkBuffer(), target.handle, whole)
            }
            VkCompute.barrier(
                commands, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT or VK10.VK_PIPELINE_STAGE_HOST_BIT, ALL_MEMORY or VK10.VK_ACCESS_HOST_READ_BIT, stack
            )
            VkCompute.vkCheck(VK10.vkEndCommandBuffer(commands), "vkEndCommandBuffer")
            encoder.execute(commands)
        }
        fenceSlot(encoder)
        return Readback(target, span, encoder.createFence())
    }

    /** Frees [readback]'s buffer once the GPU is done with it. */
    fun release(readback: Readback) = retire(readback.buffer, readback.fence)

    /** Called once per frame: the next dispatches use the next slot; replaced buffers the GPU is done with go away. */
    fun endFrame() {
        frame++
        if (retired.isEmpty()) return
        val vma = device?.vma() ?: return
        val iterator = retired.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.fence.awaitCompletion(0)) continue
            VkCompute.destroyBuffer(vma, entry.buffer)
            entry.fence.close()
            iterator.remove()
        }
    }

    /** Releases everything once the device is idle; called before the renderer shuts down. */
    @JvmStatic
    fun close() {
        val device = device ?: return
        VK10.vkDeviceWaitIdle(device.vkDevice())
        val vma = device.vma()
        for (entry in retired) {
            VkCompute.destroyBuffer(vma, entry.buffer)
            entry.fence.close()
        }
        retired.clear()
        for (i in 0 until SLOTS) {
            upload[i]?.let { VkCompute.destroyBuffer(vma, it) }
            upload[i] = null
            if (pools[i] != 0L) VK10.vkDestroyDescriptorPool(device.vkDevice(), pools[i], null)
            pools[i] = 0L
            slotFences[i]?.close()
            slotFences[i] = null
        }
        mesh?.let { VkCompute.destroyBuffer(vma, it) }
        mesh = null
        pipeline?.let { VkCompute.destroyPipeline(device.vkDevice(), it) }
        pipeline = null
        this.device = null
    }

    private fun device(): VulkanDevice = device
        ?: ((RenderSystem.getDevice() as FrontendGpuDeviceAccessor).`mobium$backend`() as VulkanDevice).also { device = it }

    /** The frame's slot: on its first use in a frame, waits until the GPU is done with it (normally long since). */
    private fun beginSlot(device: VulkanDevice) {
        if (slotFrame == frame) return
        slotFrame = frame
        slot = (frame % SLOTS).toInt()
        slotFences[slot]?.let { fence ->
            fence.awaitCompletion(GpuFence.NO_TIMEOUT)
            fence.close()
            slotFences[slot] = null
        }
        uploadUsed[slot] = 0
        if (pools[slot] == 0L) {
            pools[slot] = VkCompute.createDescriptorPool(device.vkDevice(), SETS_PER_FRAME, 3)
        } else {
            VK10.vkResetDescriptorPool(device.vkDevice(), pools[slot], 0)
        }
    }

    private fun fenceSlot(encoder: VulkanCommandEncoder) {
        if (slotFences[slot] == null) slotFences[slot] = encoder.createFence()
    }

    /** [bytes] in the slot's upload buffer, at an offset storage buffers may start at; grows the buffer if needed. */
    private fun reserve(encoder: VulkanCommandEncoder, vma: Long, bytes: Long): Long {
        val offset = (uploadUsed[slot] + alignment - 1) / alignment * alignment
        val buffer = upload[slot]
        if (buffer != null && offset + bytes <= buffer.size) {
            uploadUsed[slot] = offset + bytes
            return offset
        }
        // Data of this frame already in the old buffer stays there; commands recorded before still read it.
        var size = maxOf(INITIAL_UPLOAD_BYTES, buffer?.size ?: 0L)
        while (size < bytes) size *= 2
        if (buffer != null) retire(buffer, encoder.createFence())
        upload[slot] = VkCompute.createBuffer(vma, size, STORAGE or VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VkCompute.UPLOAD)
        uploadUsed[slot] = bytes
        return 0L
    }

    /** The mesh buffer, at least [bytes] large; a grown one gets the uploaded quads copied over in [commands]. */
    private fun meshBuffer(commands: VkCommandBuffer, encoder: VulkanCommandEncoder, vma: Long, bytes: Long, stack: MemoryStack): VkCompute.Buffer {
        val current = mesh
        if (current != null && current.size >= bytes) return current
        var size = maxOf(INITIAL_MESH_BYTES, current?.size ?: 0L)
        while (size < bytes) size *= 2
        val usage = STORAGE or VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT or VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
        val grown = VkCompute.createBuffer(vma, size, usage, VkCompute.DEVICE)
        if (current != null) {
            val uploaded = MeshPool.uploadedQuads * MESH_QUAD_BYTES
            if (uploaded > 0) {
                VK10.vkCmdCopyBuffer(commands, current.handle, grown.handle, VkBufferCopy.calloc(1, stack).srcOffset(0).dstOffset(0).size(uploaded))
                VkCompute.barrier(commands, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT, stack)
            }
            retire(current, encoder.createFence())
        }
        mesh = grown
        return grown
    }

    private fun retire(buffer: VkCompute.Buffer, fence: GpuFence) {
        retired += Retired(buffer, fence)
    }

    /** Compiles the shader with the float modes the device supports and creates the pipeline, once. */
    private fun pipeline(device: VulkanDevice): VkCompute.Pipeline {
        pipeline?.let { return it }
        val defines = ArrayList<String>()
        MemoryStack.stackPush().use { stack ->
            val physical = device.vkDevice().physicalDevice
            val vulkan12 = VkPhysicalDeviceVulkan12Properties.calloc(stack).`sType$Default`()
            val properties = VkPhysicalDeviceProperties2.calloc(stack).`sType$Default`().pNext(vulkan12.address())
            VK11.vkGetPhysicalDeviceProperties2(physical, properties)
            val limits = properties.properties().limits()
            maxGroups = limits.maxComputeWorkGroupCount(0)
            alignment = maxOf(4L, limits.minStorageBufferOffsetAlignment())
            if (vulkan12.shaderRoundingModeRTEFloat32() && vulkan12.shaderRoundingModeRTEFloat64()) defines += "MOBIUM_FLOAT_RTE"
            if (vulkan12.shaderSignedZeroInfNanPreserveFloat32() && vulkan12.shaderSignedZeroInfNanPreserveFloat64()) defines += "MOBIUM_FLOAT_PRESERVE"
            if (vulkan12.shaderDenormPreserveFloat32() && vulkan12.denormBehaviorIndependence() == VK12.VK_SHADER_FLOAT_CONTROLS_INDEPENDENCE_ALL) {
                defines += "MOBIUM_FLOAT_DENORM"
            }
        }
        val source = VulkanExpand::class.java.getResourceAsStream(SHADER)?.use { String(it.readAllBytes()) }
            ?: throw IllegalStateException("$SHADER is missing")
        val spirv = VkCompute.compile("expand_parts.comp", source, defines)
        try {
            val created = VkCompute.createPipeline(device.vkDevice(), spirv, 3, PUSH_WORDS * 4)
            pipeline = created
            floatModes = if (defines.isEmpty()) "none" else defines.joinToString { it.removePrefix("MOBIUM_FLOAT_").lowercase() }
            Mobium.LOGGER.info("Mobium part transform: Vulkan compute pipeline ready (float modes: {}, max groups {})", floatModes, maxGroups)
            return created
        } finally {
            MemoryUtil.memFree(spirv)
        }
    }
}
