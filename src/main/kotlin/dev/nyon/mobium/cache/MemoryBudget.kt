package dev.nyon.mobium.cache

/**
 * Module 1's memory budget (`cache.memoryBudgetMb`, default 128 MB), without Minecraft classes, unit-tested. It counts
 * the cached vertex data, which lives once in a GPU buffer and once in system memory (the CPU copy is what the
 * camera-relative uploads start from).
 *
 * Above the budget the farthest objects are evicted until [usedBytes] is at most [TARGET_FRACTION] of it; evicted
 * objects take the vanilla path. They may be captured again once usage has fallen below [RESUME_FRACTION], so a
 * full cache does not evict and recapture the same objects every frame.
 */
class MemoryBudget(budgetBytes: Long) {
    var budgetBytes: Long = budgetBytes
        private set

    var usedBytes = 0L
        private set

    val isOver: Boolean
        get() = usedBytes > budgetBytes

    /** True while evicted objects should stay evicted. */
    val isTight: Boolean
        get() = usedBytes > (budgetBytes * RESUME_FRACTION).toLong()

    fun resize(budgetBytes: Long) {
        this.budgetBytes = budgetBytes
    }

    fun add(bytes: Long) {
        usedBytes += bytes
    }

    fun remove(bytes: Long) {
        usedBytes -= bytes
    }

    fun reset() {
        usedBytes = 0L
    }

    /**
     * Picks the objects to evict: farthest first ([distancesSq], squared distance to the camera), until usage is at
     * most [TARGET_FRACTION] of the budget. Returns their indices into the first [count] elements of the arrays.
     */
    fun selectEvictions(distancesSq: DoubleArray, bytes: LongArray, count: Int): IntArray {
        if (!isOver) return IntArray(0)
        val target = (budgetBytes * TARGET_FRACTION).toLong()
        val order = (0 until count).sortedByDescending { distancesSq[it] }
        var used = usedBytes
        val result = ArrayList<Int>()
        for (index in order) {
            if (used <= target) break
            used -= bytes[index]
            result += index
        }
        return result.toIntArray()
    }

    companion object {
        const val TARGET_FRACTION = 0.9
        const val RESUME_FRACTION = 0.75
        const val BYTES_PER_MB = 1024L * 1024L
    }
}
