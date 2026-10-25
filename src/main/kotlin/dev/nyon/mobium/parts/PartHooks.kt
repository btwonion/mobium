@file:Suppress("FunctionName")

package dev.nyon.mobium.parts

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.vertex.VertexFormat
import net.minecraft.client.renderer.StagedVertexBuffer

/**
 * Added to `BufferBuilder` by `BufferBuilderMixin`: which staged draw the builder writes for (set when
 * `StagedVertexBuffer` opens it; null for every other builder) and Module 2's reservation of vertices that the
 * expand pass writes on the GPU.
 */
interface PartTarget {
    fun `mobium$staged`(): StagedVertexBuffer?

    fun `mobium$draw`(): StagedVertexBuffer.Draw?

    /** Bytes the draw already holds from its earlier builders; this builder's vertices come after them. */
    fun `mobium$drawBytesBefore`(): Int

    fun `mobium$tag`(staged: StagedVertexBuffer, draw: StagedVertexBuffer.Draw, drawBytesBefore: Int)

    fun `mobium$format`(): VertexFormat

    fun `mobium$topology`(): PrimitiveTopology

    fun `mobium$building`(): Boolean

    fun `mobium$vertexCount`(): Int

    /**
     * Ends the vertex in progress as the next `addVertex` would, then reserves [count] vertices without writing them
     * (they keep whatever the staging memory held) and leaves the builder as after a Sodium push of them. Returns the
     * byte offset of the first reserved vertex in this builder.
     */
    fun `mobium$reserve`(count: Int): Int
}

/** Added to `StagedVertexBuffer.Draw` by `StagedDrawMixin`. */
interface PartDraw {
    fun `mobium$format`(): VertexFormat

    /** True for draws whose quads are sorted on upload: the upload reads their vertices on the CPU. */
    fun `mobium$sorted`(): Boolean

    fun `mobium$vertexBufferSize`(): Int

    /** Byte offset of the draw in the uploaded vertex buffer; valid after `upload()`. */
    fun `mobium$vertexOffset`(): Int

    /** The draw's index in the current [PartBatch] of its staged buffer, or -1. */
    fun `mobium$slot`(): Int

    fun `mobium$setSlot`(slot: Int)
}

/** Added to `StagedVertexBuffer` by `StagedVertexBufferMixin`. */
interface PartStaged {
    fun `mobium$batch`(): PartBatch?

    fun `mobium$setBatch`(batch: PartBatch)

    /** The pooled vertex buffer of this frame; valid between `upload()` and `endDraw()`. */
    fun `mobium$vertexBuffer`(): GpuBuffer?
}

/** Added to `ModelPart.Cube` (and so to EMF's `EMFCube`) by `CubeMixin`: where [MeshPool] keeps its mesh. */
interface PartMeshHolder {
    fun `mobium$meshGeneration`(): Int

    /** The mesh's first quad in the pool, or -1 if the cube cannot be expanded on the GPU. */
    fun `mobium$meshQuad`(): Int

    fun `mobium$meshQuads`(): Int

    fun `mobium$setMesh`(generation: Int, quad: Int, quads: Int)
}

/** Added to `PoseStack.Pose` by `PoseAccessor`: whether its normals need no normalisation. */
interface PoseNormals {
    fun `mobium$trustedNormals`(): Boolean
}
