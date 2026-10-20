package dev.nyon.mobium.bench

import kotlin.math.ceil

/**
 * Summary of a benchmark run.
 *
 * @param frames is the number of recorded frames.
 * @param avgFps is frames per second over the whole run (frames / total time).
 * @param low1Fps is the "1% low": 1000 / the mean frame time of the slowest 1% of frames (at least one frame).
 * @param avgMs is the mean frame time.
 * @param p95Ms is the 95th percentile frame time (nearest rank).
 */
data class FrameSummary(val frames: Int, val avgFps: Double, val low1Fps: Double, val avgMs: Double, val p95Ms: Double)

/** Pure statistics over recorded frame times; no Minecraft classes, unit-tested. */
object FrameStats {
    fun summarize(frameNanos: LongArray, count: Int): FrameSummary {
        require(count in 1..frameNanos.size) { "count must be between 1 and ${frameNanos.size}, was $count" }
        val sortedMs = DoubleArray(count) { frameNanos[it] / NANOS_PER_MS }
        sortedMs.sort()

        val totalMs = sortedMs.sum()
        val avgMs = totalMs / count
        val slowest = ceil(count * 0.01).toInt().coerceAtLeast(1)
        var slowestSum = 0.0
        for (i in count - slowest until count) slowestSum += sortedMs[i]

        return FrameSummary(
            frames = count,
            avgFps = 1000.0 / avgMs,
            low1Fps = 1000.0 / (slowestSum / slowest),
            avgMs = avgMs,
            p95Ms = percentile(sortedMs, 0.95)
        )
    }

    /** Nearest-rank percentile of an ascending array; [fraction] in (0, 1]. */
    fun percentile(sorted: DoubleArray, fraction: Double): Double {
        require(sorted.isNotEmpty()) { "no values" }
        require(fraction > 0.0 && fraction <= 1.0) { "fraction must be in (0, 1], was $fraction" }
        val rank = ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** Median of [values] (mean of the middle two for an even count). */
    fun median(values: DoubleArray): Double {
        require(values.isNotEmpty()) { "no values" }
        val sorted = values.sortedArray()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    /** Mean of column [column] in a row-major table with [columns] columns and [rows] rows, in milliseconds. */
    fun columnMeanMs(values: LongArray, rows: Int, columns: Int, column: Int): Double {
        if (rows == 0) return 0.0
        var sum = 0L
        for (row in 0 until rows) sum += values[row * columns + column]
        return sum / NANOS_PER_MS / rows
    }

    const val NANOS_PER_MS = 1_000_000.0
}
