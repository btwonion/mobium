package dev.nyon.mobium.cache

import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.rendertype.RenderType
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * Captures what vanilla's feature renderers write for one object. While [active], a mixin on
 * `RenderTypeFeatureRenderer.getVertexBuilder` hands out [vertexBuilder] instead of the frame's shared staged buffer, so
 * vanilla's own `ModelFeatureRenderer.prepareModel` (with every mod's hooks on it) writes the vertices and Mobium only
 * keeps them. Render thread only.
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

    /** Ends the capture and copies every non-empty buffer into its own native memory. */
    fun end(): List<CapturedMesh> {
        active = false
        val meshes = ArrayList<CapturedMesh>(builders.size)
        for ((renderType, builder) in builders) {
            builder.build()?.use { mesh ->
                val source = mesh.vertexBuffer()
                val copy = MemoryUtil.memAlloc(source.remaining())
                MemoryUtil.memCopy(source, copy)
                meshes += CapturedMesh(renderType, copy, mesh.drawState().vertexCount())
            }
        }
        builders.clear()
        return meshes
    }
}

/**
 * Vertices of one object for one render type, in the render type's vertex format, in native memory owned by this
 * object until [free].
 */
class CapturedMesh(@JvmField val renderType: RenderType, @JvmField val vertices: ByteBuffer, @JvmField val vertexCount: Int) {
    fun free() = MemoryUtil.memFree(vertices)
}
