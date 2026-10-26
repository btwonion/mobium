package dev.nyon.mobium.parts

import net.minecraft.client.renderer.StagedVertexBuffer
import org.lwjgl.system.MemoryUtil

/**
 * One staged vertex buffer's records for the expand pass of this frame (`expand_parts.comp`, which documents the
 * layout): per compiled part one part record of [PART_WORDS] words (pose, normal matrix, colour, overlay, light,
 * flags, draw slot, sprite, Iris's ids), per cube one cube record of [CUBE_WORDS] words (first quad in the dispatch,
 * first quad of its mesh, its part, its first vertex in the draw), and per draw that got records a slot with the byte
 * ranges reserved in it, which the upload leaves out ([ReservedRanges]). Off-heap and reused from frame to frame;
 * grows by doubling. Render thread only.
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

    /** Per slot, the byte ranges of the draw that Module 2 reserved; kept across frames and reused by slot. */
    private val reserved = ArrayList<ReservedRanges>()
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
        if (reserved.size == added) reserved.add(ReservedRanges())
        reserved[added].clear()
        return added
    }

    /** The reserved ranges of the draw in [slot]. */
    fun reserved(slot: Int): ReservedRanges = reserved[slot]

    /** The slot of [draw] if it has one in this batch this frame, else -1. */
    fun existingSlot(draw: StagedVertexBuffer.Draw): Int {
        val slot = (draw as PartDraw).`mobium$slot`()
        return if (slot >= 0 && slot < draws.size && draws[slot] === draw) slot else -1
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
        const val P_COLOR = 21
        const val P_OVERLAY = 22
        const val P_LIGHT = 23
        const val P_FLAGS = 24
        const val P_SLOT = 25
        const val P_SPRITE = 26
        const val P_IDS = 30

        const val KIND_GENERIC = 0
        const val KIND_TRANSLATION = 1
        const val KIND_IDENTITY = 2
        const val FLAG_SPRITE = 4
        const val FLAG_IRIS = 8
        const val FLAG_IRIS_FACE_NORMAL = 16
        const val FLAG_PACKED_NORMALS = 32
        const val FLAG_TRUSTED_NORMALS = 64
    }
}
