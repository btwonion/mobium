package dev.nyon.mobium.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GateBTest {
    @Test
    fun `cost adds the draw calls' CPU time back to the run without draws`() {
        // Anton's 20 Oct numbers for storage_room and control, with a made-up run without the cache's draws.
        val gate = GateB.of(
            storage = GateB.Run(1.695, 0.050),
            storageNoDraw = GateB.Run(1.355, 0.000),
            control = GateB.Run(1.218, 0.000),
            baseline = GateB.Run(1.213, 0.000)
        )!!
        assertEquals((1.355 + 0.050) / 1.218, gate.cost, 1e-9)
        assertEquals(1.355 / 1.218, gate.noDraw, 1e-9)
        assertEquals(1.695 / 1.218, gate.frame, 1e-9)
        assertEquals(0.340, gate.drawingMs, 1e-9)
        assertEquals(1.218 / 1.213, gate.control, 1e-9)
        assertTrue(gate.costPasses)
        assertTrue(gate.controlPasses)
    }

    @Test
    fun `the gate fails above its targets`() {
        val gate = GateB.of(GateB.Run(2.0, 0.1), GateB.Run(1.5, 0.0), GateB.Run(1.2, 0.0), GateB.Run(1.1, 0.0))!!
        assertFalse(gate.costPasses) // (1.5 + 0.1) / 1.2 = 1.33
        assertFalse(gate.controlPasses) // 1.2 / 1.1 = 1.09
    }

    @Test
    fun `a draw timer that is lower in the full run does not lower the cost`() {
        val gate = GateB.of(GateB.Run(1.6, 0.01), GateB.Run(1.3, 0.02), GateB.Run(1.2, 0.0), GateB.Run(1.2, 0.0))!!
        assertEquals(1.3 / 1.2, gate.cost, 1e-9)
    }

    @Test
    fun `no verdict while a run is missing or empty`() {
        val run = GateB.Run(1.0, 0.0)
        assertNull(GateB.of(null, run, run, run))
        assertNull(GateB.of(run, null, run, run))
        assertNull(GateB.of(run, run, null, run))
        assertNull(GateB.of(run, run, run, null))
        assertNull(GateB.of(run, run, GateB.Run(0.0, 0.0), run))
    }
}
