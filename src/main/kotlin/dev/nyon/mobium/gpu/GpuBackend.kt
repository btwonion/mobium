package dev.nyon.mobium.gpu

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.GpuFence
import com.mojang.renderpearl.api.commands.RenderPass
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import java.nio.ByteBuffer

/**
 * What the modules need from the GPU: buffers, uploads, fences and drawing a buffer range with a vanilla render type
 * (so vanilla's pipeline, shaders and Iris's replacements stay in charge of what is drawn). Compute dispatch joins in
 * M6 (`gpu/gl/GlCompute`, raw GL 4.3 on the buffers' handles) and M7 (Vulkan). Render thread only.
 *
 * Every buffer a module keeps is created and released here, so [bytesAllocated] is Mobium's GPU memory (F3).
 */
interface GpuBackend {
    val bytesAllocated: Long
    val buffersAllocated: Int

    fun createBuffer(label: String, usage: Int, size: Long): GpuBuffer

    fun createBuffer(label: String, usage: Int, data: ByteBuffer): GpuBuffer

    /** Closes a buffer created by this backend. */
    fun release(buffer: GpuBuffer)

    fun write(target: GpuBufferSlice, data: ByteBuffer)

    /** GPU-side copy; both buffers need the matching `USAGE_COPY_SRC` / `USAGE_COPY_DST`. */
    fun copy(source: GpuBufferSlice, target: GpuBufferSlice)

    /** A fence after everything submitted so far. */
    fun fence(): GpuFence

    /**
     * Draws [vertexCount] vertices of quads from [buffer], starting at [firstVertex], with a render type prepared for
     * this frame (its model-view matrix and uniforms are already set). Needs a sequential quad index buffer large
     * enough for the draw, see [requestQuadIndices].
     */
    fun drawQuads(renderPass: RenderPass, prepared: PreparedRenderType, buffer: GpuBuffer, firstVertex: Int, vertexCount: Int)

    /** Makes the shared sequential quad index buffer cover [vertexCount] vertices of quads. */
    fun requestQuadIndices(vertexCount: Int)
}

/** The backend in use; Renderpearl covers OpenGL and Vulkan alike. */
object Gpu {
    @JvmStatic
    val backend: GpuBackend = RenderpearlBackend
}
