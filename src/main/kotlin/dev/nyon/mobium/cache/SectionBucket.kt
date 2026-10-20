package dev.nyon.mobium.cache

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.RenderPass
import dev.nyon.mobium.gpu.Gpu
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import net.minecraft.client.renderer.rendertype.RenderType
import org.lwjgl.system.MemoryUtil

/** One cached object's mesh inside a [SectionBucket]. */
class BucketMember(@JvmField val mesh: CapturedMesh) {
    @JvmField
    var firstVertex = 0

    @JvmField
    var visibleFrame = -1L
}

/**
 * The cached meshes of one chunk section for one render type, concatenated into one GPU vertex buffer in section-local
 * space. The buffer is rebuilt on the next prepare after a member changes; each frame only the members that vanilla
 * would have drawn (marked with [markVisible]) are drawn, in contiguous runs, through [Gpu.backend] (vanilla's
 * `PreparedRenderType.drawFromBuffer`) with the section origin in the model-view matrix.
 */
class SectionBucket(
    @JvmField val sectionKey: Long,
    @JvmField val originX: Int,
    @JvmField val originY: Int,
    @JvmField val originZ: Int,
    @JvmField val renderType: RenderType
) {
    private val members = ArrayList<BucketMember>()
    private var buffer: GpuBuffer? = null

    /** True while the bucket is queued for [rebuild]; set and cleared by the owner's queue. */
    @JvmField
    var dirty = false

    @JvmField
    var visibleFrame = -1L

    @JvmField
    var prepared: PreparedRenderType? = null

    val isEmpty: Boolean
        get() = members.isEmpty()

    val memberCount: Int
        get() = members.size

    val vertexCount: Int
        get() {
            var count = 0
            for (i in members.indices) count += members[i].mesh.vertexCount
            return count
        }

    /** Adds a member; the owner queues the bucket for [rebuild] (see [dirty]). */
    fun add(member: BucketMember) {
        members += member
    }

    /** Removes a member; the owner queues the bucket for [rebuild] (see [dirty]). */
    fun remove(member: BucketMember) {
        members.remove(member)
    }

    /** Concatenates the members into a new GPU buffer and assigns each member its first vertex. */
    fun rebuild() {
        dirty = false
        buffer?.let(Gpu.backend::release)
        buffer = null
        if (members.isEmpty()) return

        var bytes = 0
        for (member in members) bytes += member.mesh.vertices.remaining()
        val data = MemoryUtil.memAlloc(bytes)
        try {
            var vertex = 0
            for (member in members) {
                member.firstVertex = vertex
                vertex += member.mesh.vertexCount
                data.put(member.mesh.vertices.duplicate())
            }
            data.flip()
            buffer = Gpu.backend.createBuffer("Mobium section bucket", GpuBuffer.USAGE_VERTEX, data)
        } finally {
            MemoryUtil.memFree(data)
        }
    }

    /**
     * Draws the members whose [BucketMember.visibleFrame] equals [frame], merging neighbours into one draw call, and
     * returns how many members were drawn. [prepared] must have been set for this frame.
     */
    fun draw(renderPass: RenderPass, frame: Long): Int {
        val buffer = buffer ?: return 0
        val prepared = prepared ?: return 0
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
    }
}
