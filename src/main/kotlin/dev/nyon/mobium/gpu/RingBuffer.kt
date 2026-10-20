package dev.nyon.mobium.gpu

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.GpuFence
import java.nio.ByteBuffer

/**
 * Per-frame GPU data (Module 2's part records, M6) in [RingAllocator.FRAMES_IN_FLIGHT] buffers used round-robin. At
 * [beginFrame] the slot of three frames ago is reused once its fence says the GPU is done with it; [upload] places
 * data in the current slot and grows it by doubling when needed (copying what this frame already uploaded); [endFrame]
 * fences the slot. Render thread only, no allocation per upload unless the slot grows.
 *
 * @param usage is ORed with `USAGE_COPY_SRC | USAGE_COPY_DST` (growing copies on the GPU).
 */
class RingBuffer(
    private val label: String,
    usage: Int,
    initialCapacity: Long,
    private val backend: GpuBackend = Gpu.backend
) : AutoCloseable {
    private val usage = usage or GpuBuffer.USAGE_COPY_SRC or GpuBuffer.USAGE_COPY_DST
    private val allocator = RingAllocator(initialCapacity)
    private val buffers = arrayOfNulls<GpuBuffer>(allocator.slots)
    private val fences = arrayOfNulls<GpuFence>(allocator.slots)

    /** The current frame's buffer; valid until the slot grows. */
    val currentBuffer: GpuBuffer
        get() = buffer(allocator.slot)

    fun beginFrame() {
        val slot = allocator.nextFrame()
        fences[slot]?.let { fence ->
            fence.awaitCompletion(GpuFence.NO_TIMEOUT)
            fence.close()
            fences[slot] = null
        }
    }

    /** Uploads [data] (position to limit) into the current slot and returns where it landed. */
    fun upload(data: ByteBuffer): GpuBufferSlice {
        val bytes = data.remaining().toLong()
        var offset = allocator.allocate(bytes)
        if (offset < 0) {
            grow(allocator.capacityFor(bytes))
            offset = allocator.allocate(bytes)
        }
        val slice = currentBuffer.slice(offset, bytes)
        backend.write(slice, data)
        return slice
    }

    fun endFrame() {
        val slot = allocator.slot
        fences[slot]?.close()
        fences[slot] = backend.fence()
    }

    private fun buffer(slot: Int): GpuBuffer {
        val existing = buffers[slot]
        if (existing != null && existing.size() >= allocator.capacity(slot)) return existing
        val created = backend.createBuffer("$label #$slot", usage, allocator.capacity(slot))
        if (existing != null) backend.release(existing)
        buffers[slot] = created
        return created
    }

    private fun grow(capacity: Long) {
        val slot = allocator.slot
        val old = buffers[slot]
        val used = allocator.used
        allocator.grow(capacity)
        val grown = backend.createBuffer("$label #$slot", usage, capacity)
        if (old != null) {
            if (used > 0) backend.copy(old.slice(0, used), grown.slice(0, used))
            backend.release(old)
        }
        buffers[slot] = grown
    }

    override fun close() {
        for (i in buffers.indices) {
            fences[i]?.close()
            fences[i] = null
            buffers[i]?.let(backend::release)
            buffers[i] = null
        }
    }
}
