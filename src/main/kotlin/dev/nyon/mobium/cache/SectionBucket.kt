package dev.nyon.mobium.cache

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.RenderPass
import dev.nyon.mobium.gpu.Gpu
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import net.minecraft.client.renderer.rendertype.RenderType
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * One cached object's mesh inside a [SectionBucket], with the world position its local space starts at (the block
 * origin for block entities).
 */
class BucketMember(
    @JvmField val mesh: CapturedMesh,
    @JvmField val originX: Double,
    @JvmField val originY: Double,
    @JvmField val originZ: Double
) {
    @JvmField
    var firstVertex = 0

    @JvmField
    var visibleFrame = -1L
}

/** The render types of the cache's buckets, each prepared at most once per frame. */
class CachedRenderType(@JvmField val renderType: RenderType) {
    @JvmField
    var preparedFrame = -1L

    @JvmField
    var prepared: PreparedRenderType? = null
}

/**
 * The cached meshes of one chunk section for one render type, concatenated into one GPU vertex buffer.
 *
 * The buffer holds **camera-relative** positions, exactly what vanilla writes: vanilla's entity and text shaders take
 * the fog distance from the raw `Position` attribute (`fog_spherical_distance(Position)` in `entity.vsh` and
 * `text.vsh`), so a section offset in the model-view matrix would draw cached objects with the fog of a closer
 * object. The CPU keeps each member's local vertices; [upload] adds `origin - camera` (as a float, like vanilla's
 * `PoseStack.translate`) to every position and writes the result when the bucket is drawn after the camera moved or a
 * member changed. With a still camera nothing is written. Each frame only the members vanilla would have drawn (marked
 * with [BucketMember.visibleFrame]) are drawn, in contiguous runs, with the level's own model-view matrix.
 */
class SectionBucket(@JvmField val sectionKey: Long, @JvmField val type: CachedRenderType) {
    private val members = ArrayList<BucketMember>()
    private var buffer: GpuBuffer? = null
    private var baseX = Double.NaN
    private var baseY = Double.NaN
    private var baseZ = Double.NaN
    private var layoutDirty = false

    /** True while the bucket is queued in its owner's dirty list. */
    @JvmField
    var dirty = false

    @JvmField
    var visibleFrame = -1L

    @JvmField
    var vertexCount = 0

    @JvmField
    var byteCount = 0

    val renderType: RenderType
        get() = type.renderType

    val isEmpty: Boolean
        get() = members.isEmpty()

    val memberCount: Int
        get() = members.size

    /** GPU bytes held by this bucket. */
    val gpuBytes: Long
        get() = buffer?.size() ?: 0L

    fun add(member: BucketMember) {
        members += member
        vertexCount += member.mesh.vertexCount
        byteCount += member.mesh.bytes
        layoutDirty = true
    }

    fun remove(member: BucketMember) {
        if (!members.remove(member)) return
        vertexCount -= member.mesh.vertexCount
        byteCount -= member.mesh.bytes
        layoutDirty = true
    }

    /** True when [upload] has to run before this bucket can be drawn from the camera at x, y, z. */
    fun needsUpload(cameraX: Double, cameraY: Double, cameraZ: Double): Boolean =
        layoutDirty || buffer == null || cameraX != baseX || cameraY != baseY || cameraZ != baseZ

    /**
     * Writes all members, moved to camera-relative space, into the GPU buffer. [scratch] must hold at least
     * [byteCount] bytes; it is overwritten.
     */
    fun upload(cameraX: Double, cameraY: Double, cameraZ: Double, scratch: ByteBuffer) {
        if (layoutDirty) {
            var vertex = 0
            for (i in members.indices) {
                val member = members[i]
                member.firstVertex = vertex
                vertex += member.mesh.vertexCount
            }
            layoutDirty = false
        }
        val target = MemoryUtil.memAddress(scratch)
        var offset = 0L
        for (i in members.indices) {
            val member = members[i]
            val mesh = member.mesh
            val bytes = mesh.bytes.toLong()
            MemoryUtil.memCopy(mesh.address, target + offset, bytes)
            val dx = (member.originX - cameraX).toFloat()
            val dy = (member.originY - cameraY).toFloat()
            val dz = (member.originZ - cameraZ).toFloat()
            val stride = mesh.vertexSize.toLong()
            var position = target + offset
            val end = position + bytes
            while (position < end) {
                MemoryUtil.memPutFloat(position, MemoryUtil.memGetFloat(position) + dx)
                MemoryUtil.memPutFloat(position + 4, MemoryUtil.memGetFloat(position + 4) + dy)
                MemoryUtil.memPutFloat(position + 8, MemoryUtil.memGetFloat(position + 8) + dz)
                position += stride
            }
            offset += bytes
        }

        val data = MemoryUtil.memByteBuffer(target, byteCount)
        var buffer = buffer
        if (buffer == null || buffer.size() < byteCount) {
            buffer?.let(Gpu.backend::release)
            // Room to grow by a few members before the buffer has to be replaced.
            val size = byteCount + byteCount / 4 + GROWTH_BYTES
            buffer = Gpu.backend.createBuffer(LABEL, GpuBuffer.USAGE_VERTEX or GpuBuffer.USAGE_COPY_DST, size.toLong())
            this.buffer = buffer
        }
        Gpu.backend.write(buffer.slice(0, byteCount.toLong()), data)
        baseX = cameraX
        baseY = cameraY
        baseZ = cameraZ
    }

    /**
     * Draws the members whose [BucketMember.visibleFrame] equals [frame], merging neighbours into one draw call, and
     * returns how many members were drawn.
     */
    fun draw(renderPass: RenderPass, prepared: PreparedRenderType, frame: Long): Int {
        val buffer = buffer ?: return 0
        var drawn = 0
        var runStart = -1
        var runVertices = 0
        for (i in 0..members.size) {
            val member = if (i < members.size) members[i] else null
            if (member != null && member.visibleFrame == frame) {
                if (runStart < 0) runStart = member.firstVertex
                runVertices += member.mesh.vertexCount
                drawn++
                continue
            }
            if (runStart >= 0) {
                Gpu.backend.drawQuads(renderPass, prepared, buffer, runStart, runVertices)
                runStart = -1
                runVertices = 0
            }
        }
        return drawn
    }

    fun close() {
        buffer?.let(Gpu.backend::release)
        buffer = null
        members.clear()
        vertexCount = 0
        byteCount = 0
    }

    private companion object {
        const val LABEL = "Mobium section bucket"
        const val GROWTH_BYTES = 4096
    }
}
