package dev.nyon.mobium.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GateCTest {
    @Test
    fun `the CPU ratio counts the shadow pass's prepare stage`() {
        // The dev client's D numbers of 26 Oct 2026.
        val gate = GateC.of(GateC.Run(31.76, 11.015, 3.015), GateC.Run(28.70, 5.539, 1.093))!!
        assertEquals(14.03 / 6.632, gate.cpu, 1e-3)
        assertTrue(gate.cpuPasses)
        assertEquals(28.70 / 31.76, gate.frame, 1e-9)
        assertTrue(gate.framePasses)
    }

    @Test
    fun `a frame up to 3 percent slower counts as no slower`() {
        assertTrue(GateC.of(GateC.Run(10.0, 6.0, 0.0), GateC.Run(10.3, 4.0, 0.0))!!.framePasses)
        assertFalse(GateC.of(GateC.Run(10.0, 6.0, 0.0), GateC.Run(10.31, 4.0, 0.0))!!.framePasses)
        assertFalse(GateC.of(GateC.Run(10.0, 6.0, 0.0), GateC.Run(9.0, 4.5, 0.0))!!.cpuPasses)
    }

    @Test
    fun `missing or empty runs give no numbers`() {
        assertNull(GateC.of(null, GateC.Run(1.0, 1.0, 0.0)))
        assertNull(GateC.of(GateC.Run(1.0, 1.0, 0.0), null))
        assertNull(GateC.of(GateC.Run(1.0, 1.0, 0.0), GateC.Run(1.0, 0.0, 0.0)))
        assertNull(GateC.of(GateC.Run(0.0, 1.0, 0.0), GateC.Run(1.0, 1.0, 0.0)))
    }
}
