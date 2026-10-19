package dev.nyon.mobium.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FrameStatsTest {
    private fun ms(vararg values: Double) = LongArray(values.size) { (values[it] * 1_000_000).toLong() }

    @Test
    fun `steady frames give matching averages`() {
        val summary = FrameStats.summarize(ms(10.0, 10.0, 10.0, 10.0), 4)
        assertEquals(4, summary.frames)
        assertEquals(10.0, summary.avgMs, 1e-9)
        assertEquals(100.0, summary.avgFps, 1e-9)
        assertEquals(100.0, summary.low1Fps, 1e-9)
        assertEquals(10.0, summary.p95Ms, 1e-9)
    }

    @Test
    fun `one percent low averages the slowest percent of frames`() {
        // 200 frames: 198 at 5 ms, two at 20 ms -> the slowest 1 % are those two frames.
        val frames = LongArray(200) { if (it < 198) 5_000_000L else 20_000_000L }
        val summary = FrameStats.summarize(frames, 200)
        assertEquals(50.0, summary.low1Fps, 1e-9)
        assertEquals(5.0, summary.p95Ms, 1e-9)
        assertEquals((198 * 5.0 + 2 * 20.0) / 200, summary.avgMs, 1e-9)
    }

    @Test
    fun `one percent low uses at least one frame`() {
        val summary = FrameStats.summarize(ms(4.0, 8.0, 16.0), 3)
        assertEquals(1000.0 / 16.0, summary.low1Fps, 1e-9)
    }

    @Test
    fun `only the first count frames are used`() {
        val summary = FrameStats.summarize(ms(10.0, 20.0, 1000.0), 2)
        assertEquals(15.0, summary.avgMs, 1e-9)
    }

    @Test
    fun `percentile uses the nearest rank`() {
        val sorted = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)
        assertEquals(10.0, FrameStats.percentile(sorted, 0.95), 1e-9)
        assertEquals(5.0, FrameStats.percentile(sorted, 0.5), 1e-9)
        assertEquals(1.0, FrameStats.percentile(sorted, 0.01), 1e-9)
    }

    @Test
    fun `column mean reads a row-major table`() {
        val table = longArrayOf(1_000_000, 4_000_000, 3_000_000, 8_000_000)
        assertEquals(2.0, FrameStats.columnMeanMs(table, rows = 2, columns = 2, column = 0), 1e-9)
        assertEquals(6.0, FrameStats.columnMeanMs(table, rows = 2, columns = 2, column = 1), 1e-9)
        assertEquals(0.0, FrameStats.columnMeanMs(table, rows = 0, columns = 2, column = 1), 1e-9)
    }

    @Test
    fun `median of odd and even counts`() {
        assertEquals(2.0, FrameStats.median(doubleArrayOf(3.0, 1.0, 2.0)), 1e-9)
        assertEquals(2.5, FrameStats.median(doubleArrayOf(4.0, 1.0, 3.0, 2.0)), 1e-9)
        assertEquals(7.0, FrameStats.median(doubleArrayOf(7.0)), 1e-9)
    }

    @Test
    fun `invalid input is rejected`() {
        assertThrows<IllegalArgumentException> { FrameStats.median(DoubleArray(0)) }
        assertThrows<IllegalArgumentException> { FrameStats.summarize(LongArray(3), 0) }
        assertThrows<IllegalArgumentException> { FrameStats.summarize(LongArray(3), 4) }
        assertThrows<IllegalArgumentException> { FrameStats.percentile(DoubleArray(0), 0.5) }
    }
}
