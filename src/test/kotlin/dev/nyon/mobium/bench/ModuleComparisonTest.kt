package dev.nyon.mobium.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModuleComparisonTest {
    @Test
    fun `the CPU ratio counts the shadow pass's prepare stage`() {
        // Numbers measured in setup D in the dev client.
        val comparison = ModuleComparison.of(ModuleComparison.Run(31.76, 11.015, 3.015), ModuleComparison.Run(28.70, 5.539, 1.093))!!
        assertEquals(14.03 / 6.632, comparison.cpu, 1e-3)
        assertEquals(28.70 / 31.76, comparison.frame, 1e-9)
        assertTrue(comparison.framePasses)
    }

    @Test
    fun `a frame up to 3 percent slower counts as no slower`() {
        assertTrue(ModuleComparison.of(ModuleComparison.Run(10.0, 6.0, 0.0), ModuleComparison.Run(10.3, 4.0, 0.0))!!.framePasses)
        assertFalse(ModuleComparison.of(ModuleComparison.Run(10.0, 6.0, 0.0), ModuleComparison.Run(10.31, 4.0, 0.0))!!.framePasses)
    }

    @Test
    fun `no CPU work with the module gives a frame ratio only`() {
        val comparison = ModuleComparison.of(ModuleComparison.Run(2.0, 1.0, 0.0), ModuleComparison.Run(1.0, 0.0, 0.0))!!
        assertEquals(0.5, comparison.frame, 1e-9)
        assertTrue(comparison.cpu.isNaN())
    }

    @Test
    fun `missing or empty runs give no numbers`() {
        assertNull(ModuleComparison.of(null, ModuleComparison.Run(1.0, 1.0, 0.0)))
        assertNull(ModuleComparison.of(ModuleComparison.Run(1.0, 1.0, 0.0), null))
        assertNull(ModuleComparison.of(ModuleComparison.Run(0.0, 1.0, 0.0), ModuleComparison.Run(1.0, 1.0, 0.0)))
    }
}
