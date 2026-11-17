package dev.nyon.mobium.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CacheCostTest {
    @Test
    fun `cost adds the draw calls' CPU time back to the run without draws`() {
        // Measured numbers for storage_room and control, with a made-up run without the cache's draws.
        val cost = CacheCost.of(
            storage = CacheCost.Run(1.695, 0.050),
            storageNoDraw = CacheCost.Run(1.355, 0.000),
            control = CacheCost.Run(1.218, 0.000),
            baseline = CacheCost.Run(1.213, 0.000)
        )!!
        assertEquals((1.355 + 0.050) / 1.218, cost.cost, 1e-9)
        assertEquals(1.355 / 1.218, cost.noDraw, 1e-9)
        assertEquals(1.695 / 1.218, cost.frame, 1e-9)
        assertEquals(0.340, cost.drawingMs, 1e-9)
        assertEquals(1.218 / 1.213, cost.control, 1e-9)
        assertTrue(cost.controlPasses)
    }

    @Test
    fun `control more than 3 percent slower with the cache fails the release bar`() {
        val cost = CacheCost.of(CacheCost.Run(2.0, 0.1), CacheCost.Run(1.5, 0.0), CacheCost.Run(1.2, 0.0), CacheCost.Run(1.1, 0.0))!!
        assertFalse(cost.controlPasses) // 1.2 / 1.1 = 1.09
    }

    @Test
    fun `a draw timer that is lower in the full run does not lower the cost`() {
        val cost = CacheCost.of(CacheCost.Run(1.6, 0.01), CacheCost.Run(1.3, 0.02), CacheCost.Run(1.2, 0.0), CacheCost.Run(1.2, 0.0))!!
        assertEquals(1.3 / 1.2, cost.cost, 1e-9)
    }

    @Test
    fun `no numbers while a run is missing or empty`() {
        val run = CacheCost.Run(1.0, 0.0)
        assertNull(CacheCost.of(null, run, run, run))
        assertNull(CacheCost.of(run, null, run, run))
        assertNull(CacheCost.of(run, run, null, run))
        assertNull(CacheCost.of(run, run, run, null))
        assertNull(CacheCost.of(run, run, CacheCost.Run(0.0, 0.0), run))
    }
}
