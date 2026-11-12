package dev.nyon.mobium.bench

/**
 * Counts how many of a benchmark run's GPU throttle samples ([MachineState.readThrottle], once per second) named each
 * reason, for `summary.csv`'s `gpu_throttle`: the share of samples per reason, most frequent first (`pl1 92%; none 8%`,
 * no commas, as it is a CSV field). A sample naming two reasons counts for both, so the shares can add up to more than
 * 100 %. [add] allocates only the first time a name appears in a run.
 */
class ThrottleTally {
    private val names = arrayOfNulls<String>(MAX_REASONS)
    private val counts = IntArray(MAX_REASONS)
    private var size = 0

    /** Samples that named at least one reason (`none` included). */
    var samples = 0
        private set

    fun reset() {
        names.fill(null)
        counts.fill(0)
        size = 0
        samples = 0
    }

    /** Adds one sample: reasons separated by spaces or commas. An empty one (nothing could be read) does not count. */
    fun add(reasons: String) {
        val length = reasons.length
        var start = 0
        var counted = false
        while (start < length) {
            if (isSeparator(reasons[start])) {
                start++
                continue
            }
            var end = start + 1
            while (end < length && !isSeparator(reasons[end])) end++
            count(reasons, start, end)
            counted = true
            start = end
        }
        if (counted) samples++
    }

    /** The shares for `summary.csv`, or empty without samples. */
    fun summary(): String {
        if (samples == 0) return ""
        // Most frequent first; equal counts keep the order in which the reasons first appeared.
        val order = IntArray(size) { it }
        for (i in 1 until size) {
            val index = order[i]
            var j = i - 1
            while (j >= 0 && counts[order[j]] < counts[index]) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = index
        }
        val out = StringBuilder()
        for (index in order) {
            if (out.isNotEmpty()) out.append("; ")
            out.append(names[index]).append(' ').append(percent(counts[index])).append('%')
        }
        return out.toString()
    }

    /** Whole percent; `<1` for a reason seen in under 1 % of the samples, 100 only when it was in all of them. */
    private fun percent(count: Int): String = when {
        count == samples -> "100"
        count * 100L < samples -> "<1"
        else -> minOf(99L, Math.round(count * 100.0 / samples)).toString()
    }

    private fun count(reasons: String, start: Int, end: Int) {
        val length = end - start
        for (index in 0 until size) {
            val name = names[index]!!
            if (name.length == length && reasons.regionMatches(start, name, 0, length)) {
                counts[index]++
                return
            }
        }
        if (size == MAX_REASONS) return
        names[size] = reasons.substring(start, end)
        counts[size] = 1
        size++
    }

    private fun isSeparator(char: Char) = char == ' ' || char == ',' || char == '\n' || char == '\t'

    companion object {
        /** More than either driver names (`xe` on Crescent Island: 15, and `none`). */
        const val MAX_REASONS = 24
    }
}
