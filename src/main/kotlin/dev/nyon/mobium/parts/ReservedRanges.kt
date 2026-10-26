package dev.nyon.mobium.parts

import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * The byte ranges of one staged draw that Module 2 reserved, in the order they were reserved (which is their order in
 * the draw; adjacent ones merged). `StagedVertexBuffer.upload()` copies every builder slice of a draw into the staging
 * buffer; [copyWithout] copies a slice without these ranges, whose bytes are junk until the expand pass writes them.
 * Render thread only.
 */
class ReservedRanges {
    private var bounds = IntArray(64)
    private var cursor = 0

    var count = 0
        private set

    fun start(range: Int): Int = bounds[range * 2]

    fun end(range: Int): Int = bounds[range * 2 + 1]

    fun clear() {
        count = 0
        cursor = 0
    }

    /** Adds `[start, end)`, in bytes from the draw's start; ranges come in increasing order. */
    fun add(start: Int, end: Int) {
        if (count > 0 && bounds[count * 2 - 1] == start) {
            bounds[count * 2 - 1] = end
            return
        }
        if (count * 2 == bounds.size) bounds = bounds.copyOf(bounds.size * 2)
        bounds[count * 2] = start
        bounds[count * 2 + 1] = end
        count++
    }

    /**
     * Copies [slice] (all of it remaining) to [target] at its position, which is [drawOffset] bytes from the draw's
     * start, leaving out the reserved bytes, and moves [target]'s position past it, as `ByteBuffer.put` would. Slices
     * arrive in draw order.
     */
    fun copyWithout(target: ByteBuffer, slice: ByteBuffer, drawOffset: Int) {
        val length = slice.remaining()
        val source = MemoryUtil.memAddress(slice)
        val destination = MemoryUtil.memAddress(target)
        val end = drawOffset + length
        var position = drawOffset
        while (cursor < count && bounds[cursor * 2 + 1] <= position) cursor++
        var i = cursor
        while (position < end) {
            if (i < count && bounds[i * 2] <= position) {
                // Inside a reserved range: skip it, and keep it for the next slice if it goes on there.
                val rangeEnd = bounds[i * 2 + 1]
                if (rangeEnd > end) {
                    position = end
                } else {
                    position = rangeEnd
                    i++
                }
            } else {
                val copyEnd = if (i < count) minOf(bounds[i * 2], end) else end
                MemoryUtil.memCopy(source + (position - drawOffset), destination + (position - drawOffset), (copyEnd - position).toLong())
                position = copyEnd
            }
        }
        cursor = i
        target.position(target.position() + length)
    }
}
