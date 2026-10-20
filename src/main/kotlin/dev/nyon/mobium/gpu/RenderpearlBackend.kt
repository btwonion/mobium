package dev.nyon.mobium.gpu

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.GpuFence
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import java.nio.ByteBuffer

/**
 * [GpuBackend] on Renderpearl, the game's own GPU layer (docs/vanilla-notes.md, *Renderpearl*): buffers from
 * `GpuDevice.createBuffer`, uploads and fences through a `CommandEncoder`, draws through vanilla's
 * `PreparedRenderType.drawFromBuffer`, like vanilla's own feature draws.
 */
object RenderpearlBackend : GpuBackend {
    override var bytesAllocated = 0L
        private set
    override var buffersAllocated = 0
        private set

    override fun createBuffer(label: String, usage: Int, size: Long): GpuBuffer =
        track(RenderSystem.getDevice().createBuffer({ label }, usage, size))

    override fun createBuffer(label: String, usage: Int, data: ByteBuffer): GpuBuffer =
        track(RenderSystem.getDevice().createBuffer({ label }, usage, data))

    override fun release(buffer: GpuBuffer) {
        if (buffer.isClosed) return
        bytesAllocated -= buffer.size()
        buffersAllocated--
        buffer.close()
    }

    override fun write(target: GpuBufferSlice, data: ByteBuffer) =
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(target, data)

    override fun copy(source: GpuBufferSlice, target: GpuBufferSlice) =
        RenderSystem.getDevice().createCommandEncoder().copyToBuffer(source, target)

    override fun fence(): GpuFence = RenderSystem.getDevice().createCommandEncoder().createFence()

    override fun drawQuads(
        renderPass: RenderPass,
        prepared: PreparedRenderType,
        buffer: GpuBuffer,
        firstVertex: Int,
        vertexCount: Int
    ) {
        val indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
        val info = StagedVertexBuffer.ExecuteInfo(
            buffer, null, indices.type(), firstVertex, 0, vertexCount / 4 * 6, PrimitiveTopology.QUADS
        )
        prepared.drawFromBuffer(info, renderPass)
    }

    override fun requestQuadIndices(vertexCount: Int) {
        if (vertexCount > 0) RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).requestIndexCount(vertexCount / 4 * 6)
    }

    private fun track(buffer: GpuBuffer): GpuBuffer {
        bytesAllocated += buffer.size()
        buffersAllocated++
        return buffer
    }
}
