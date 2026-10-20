package dev.nyon.mobium.gpu

/**
 * The arithmetic of [RingBuffer] without GPU objects, unit-tested: [slots] regions used round-robin, one per frame in
 * flight, each handing out aligned ranges from offset 0 every time its frame comes round. A slot that is too small
 * for a frame's data doubles until it fits; the other slots grow when their turn comes and they need it.
 */
class RingAllocator(initialCapacity: Long, val alignment: Long = DEFAULT_ALIGNMENT, val slots: Int = FRAMES_IN_FLIGHT) {
    private val capacities = LongArray(slots) { initialCapacity }

    init {
        require(initialCapacity > 0) { "initial capacity must be positive, was $initialCapacity" }
        require(alignment > 0 && alignment and (alignment - 1) == 0L) { "alignment must be a power of two, was $alignment" }
        require(slots > 0) { "need at least one slot" }
    }

    /** The slot of the current frame. */
    var slot = 0
        private set

    /** Bytes handed out in the current frame, including alignment padding. */
    var used = 0L
        private set

    fun capacity(slot: Int = this.slot): Long = capacities[slot]

    /** Moves to the next slot and empties it; the caller first waits until the GPU is done with that slot. */
    fun nextFrame(): Int {
        slot = (slot + 1) % slots
        used = 0L
        return slot
    }

    /** Offset of [bytes] in the current slot, or -1 if the slot has to [grow] to [capacityFor] first. */
    fun allocate(bytes: Long): Long {
        require(bytes >= 0) { "negative size $bytes" }
        val offset = align(used)
        if (offset + bytes > capacities[slot]) return -1
        used = offset + bytes
        return offset
    }

    /** The capacity the current slot needs for [bytes] more: the current one doubled until they fit. */
    fun capacityFor(bytes: Long): Long {
        val needed = align(used) + bytes
        var capacity = capacities[slot]
        while (capacity < needed) capacity *= 2
        return capacity
    }

    /** Records that the current slot's buffer was replaced by one of [capacity] bytes; what was used stays used. */
    fun grow(capacity: Long) {
        require(capacity >= capacities[slot]) { "a slot never shrinks: $capacity < ${capacities[slot]}" }
        capacities[slot] = capacity
    }

    private fun align(offset: Long): Long = (offset + alignment - 1) and (alignment - 1).inv()

    companion object {
        /** Like vanilla's `MappableRingBuffer`: the GPU may still read the two previous frames' data. */
        const val FRAMES_IN_FLIGHT = 3

        /** Covers every buffer offset alignment a backend asks for (uniform and storage offsets included). */
        const val DEFAULT_ALIGNMENT = 256L
    }
}
