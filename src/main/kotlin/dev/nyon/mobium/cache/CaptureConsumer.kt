package dev.nyon.mobium.cache

import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.vertex.VertexFormat
import net.minecraft.client.renderer.rendertype.RenderType
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * Captures what vanilla's feature renderers write for one object. While [active], a mixin on
 * `RenderTypeFeatureRenderer.getVertexBuilder` hands out [vertexBuilder] instead of the frame's shared staged buffer, so
 * vanilla's own `prepareModel` / text glyph writer (with every mod's hooks on them) writes the vertices and Mobium only
 * keeps them, in the vertex format the render type has at that moment. Render thread only.
 */
object CaptureConsumer {
    private const val INITIAL_CAPACITY = 16 * 1024

    @JvmField
    var active = false

    private val storage = HashMap<RenderType, ByteBufferBuilder>()
    private val builders = LinkedHashMap<RenderType, BufferBuilder>()

    @JvmStatic
    fun vertexBuilder(renderType: RenderType): VertexConsumer = builders.getOrPut(renderType) {
        val bytes = storage.getOrPut(renderType) { ByteBufferBuilder(INITIAL_CAPACITY) }
        BufferBuilder(bytes, renderType.primitiveTopology(), renderType.format())
    }

    fun begin() {
        check(!active) { "Capture already running" }
        builders.clear()
        active = true
    }

    /**
     * Ends the capture and copies every non-empty buffer into its own native memory, in the order the render types
     * were first used.
     */
    fun end(): List<CapturedMesh> {
        active = false
        val meshes = ArrayList<CapturedMesh>(builders.size)
        try {
            for ((renderType, builder) in builders) {
                builder.build()?.use { mesh ->
                    val source = mesh.vertexBuffer()
                    val copy = MemoryUtil.memAlloc(source.remaining())
                    MemoryUtil.memCopy(source, copy)
                    val state = mesh.drawState()
                    meshes += CapturedMesh(
                        renderType, copy, state.vertexCount(), state.format(), state.primitiveTopology()
                    )
                }
            }
        } catch (e: Throwable) {
            meshes.forEach(CapturedMesh::free)
            throw e
        } finally {
            builders.clear()
        }
        return meshes
    }

    /** Drops what a failed capture wrote so far. */
    fun abort() {
        active = false
        for (builder in builders.values) builder.build()?.close()
        builders.clear()
    }
}

/**
 * Vertices of one object for one render type, in block-local space (or entity-local for entities), in native memory
 * owned by this object until [free]. [format] is the format the vertices were written in, which Iris extends while a
 * shader pack is on.
 *
 * Only quads whose `Position` is the first attribute (three floats) can be drawn from the cache, because drawing moves
 * them to camera-relative space by adding an offset to those three floats ([SectionBucket]).
 */
class CapturedMesh(
    @JvmField val renderType: RenderType,
    @JvmField val vertices: ByteBuffer,
    @JvmField val vertexCount: Int,
    @JvmField val format: VertexFormat,
    @JvmField val topology: PrimitiveTopology
) {
    @JvmField
    val address: Long = MemoryUtil.memAddress(vertices)

    @JvmField
    val vertexSize: Int = format.vertexSize

    val bytes: Int
        get() = vertices.remaining()

    /** True when [SectionBucket] can draw this mesh: quads with three float positions at offset 0. */
    val drawable: Boolean
        get() {
            if (topology != PrimitiveTopology.QUADS || vertexCount % 4 != 0) return false
            val position = format.getElement(DefaultVertexFormat.POSITION_SEMANTIC_NAME) ?: return false
            return position.offset() == 0 && position.format() == GpuFormat.RGB32_FLOAT
        }

    fun free() = MemoryUtil.memFree(vertices)
}
