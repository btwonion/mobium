package dev.nyon.mobium.parts

import net.minecraft.client.renderer.StagedVertexBuffer
import org.lwjgl.system.MemoryUtil

/**
 * One staged vertex buffer's records for the expand pass of this frame (`expand_parts.comp`, which documents the
 * layout): per compiled part one part record of [PART_WORDS] words (pose, the vanilla normal per direction, colour,
 * overlay, light, flags, draw slot, sprite, Iris's ids), per cube one cube record of [CUBE_WORDS] words (first quad
 * in the dispatch, first quad of its mesh, its part, its first vertex in the draw), and per draw that got records a
 * slot. Off-heap and reused from frame to frame; grows by doubling. Render thread only.
 */
class PartBatch {
    @JvmField
    var partCount = 0

    @JvmField
    var cubeCount = 0

    @JvmField
    var quadCount = 0

    private var parts = MemoryUtil.nmemAlloc(INITIAL_PARTS.toLong() * PART_BYTES)
    private var partCapacity = INITIAL_PARTS
    private var cubes = MemoryUtil.nmemAlloc(INITIAL_CUBES.toLong() * CUBE_BYTES)
    private var cubeCapacity = INITIAL_CUBES

    /** The draws by slot; [slots] holds their first vertex once uploaded. */
    @JvmField
    val draws = ArrayList<StagedVertexBuffer.Draw>()
    private var slots = MemoryUtil.nmemAlloc(INITIAL_SLOTS.toLong() * 4)
    private var slotCapacity = INITIAL_SLOTS

    val isEmpty: Boolean
        get() = quadCount == 0

    val partsAddress: Long
        get() = parts

    val cubesAddress: Long
        get() = cubes

    val slotsAddress: Long
        get() = slots

    /** The slot of [draw] in this batch, adding it on first use this frame. */
    fun slotOf(draw: StagedVertexBuffer.Draw): Int {
        val partDraw = draw as PartDraw
        val slot = partDraw.`mobium$slot`()
        if (slot >= 0 && slot < draws.size && draws[slot] === draw) return slot
        val added = draws.size
        draws.add(draw)
        partDraw.`mobium$setSlot`(added)
        return added
    }

    /** Address of a new part record; the caller fills all [PART_WORDS] words. */
    fun addPart(): Long {
        if (partCount == partCapacity) {
            partCapacity *= 2
            parts = MemoryUtil.nmemRealloc(parts, partCapacity.toLong() * PART_BYTES)
        }
        return parts + (partCount++).toLong() * PART_BYTES
    }

    fun addCube(meshQuad: Int, quads: Int, part: Int, firstVertex: Int) {
        if (cubeCount == cubeCapacity) {
            cubeCapacity *= 2
            cubes = MemoryUtil.nmemRealloc(cubes, cubeCapacity.toLong() * CUBE_BYTES)
        }
        val address = cubes + (cubeCount++).toLong() * CUBE_BYTES
        MemoryUtil.memPutInt(address, quadCount)
        MemoryUtil.memPutInt(address + 4, meshQuad)
        MemoryUtil.memPutInt(address + 8, part)
        MemoryUtil.memPutInt(address + 12, firstVertex)
        quadCount += quads
    }

    /** Writes each draw's first vertex in the uploaded vertex buffer into [slots]. */
    fun resolveSlots() {
        if (draws.size > slotCapacity) {
            while (slotCapacity < draws.size) slotCapacity *= 2
            slots = MemoryUtil.nmemRealloc(slots, slotCapacity.toLong() * 4)
        }
        for (i in 0 until draws.size) {
            val draw = draws[i] as PartDraw
            MemoryUtil.memPutInt(slots + i * 4L, draw.`mobium$vertexOffset`() / draw.`mobium$format`().vertexSize)
        }
    }

    fun clear() {
        partCount = 0
        cubeCount = 0
        quadCount = 0
        for (i in 0 until draws.size) (draws[i] as PartDraw).`mobium$setSlot`(-1)
        draws.clear()
    }

    companion object {
        const val PART_WORDS = 32
        const val PART_BYTES = PART_WORDS * 4
        const val CUBE_WORDS = 4
        const val CUBE_BYTES = CUBE_WORDS * 4
        private const val INITIAL_PARTS = 4096
        private const val INITIAL_CUBES = 8192
        private const val INITIAL_SLOTS = 64

        // Part record words, as in expand_parts.comp.
        const val P_MATRIX = 0
        const val P_NORMALS = 12
        const val P_COLOR = 18
        const val P_OVERLAY = 19
        const val P_LIGHT = 20
        const val P_FLAGS = 21
        const val P_SLOT = 22
        const val P_SPRITE = 23
        const val P_IDS = 27

        const val KIND_GENERIC = 0
        const val KIND_TRANSLATION = 1
        const val KIND_IDENTITY = 2
        const val FLAG_SPRITE = 4
        const val FLAG_IRIS = 8
        const val FLAG_IRIS_FACE_NORMAL = 16
    }
}
