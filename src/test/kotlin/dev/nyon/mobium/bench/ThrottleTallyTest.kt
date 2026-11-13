package dev.nyon.mobium.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThrottleTallyTest {
    private fun tally(vararg samples: String) = ThrottleTally().apply { samples.forEach(::add) }

    @Test
    fun `no samples give an empty column`() {
        assertEquals("", ThrottleTally().summary())
        assertEquals("", tally("", " \n").summary())
    }

    @Test
    fun `shares per reason, most frequent first`() {
        val samples = Array(25) { if (it < 23) "pl1" else "none" }
        val tally = tally(*samples)
        assertEquals(25, tally.samples)
        assertEquals("pl1 92%; none 8%", tally.summary())
    }

    @Test
    fun `a sample with two reasons counts for both, whatever separates them`() {
        val tally = tally("pl1 prochot\n", "pl1, thermal", "pl1,prochot", "none")
        assertEquals(4, tally.samples)
        assertEquals("pl1 75%; prochot 50%; thermal 25%; none 25%", tally.summary())
    }

    @Test
    fun `rare reasons are not rounded away and only a reason in every sample reads 100`() {
        val samples = Array(300) { if (it == 0) "pl2" else "pl1" }
        assertEquals("pl1 99%; pl2 <1%", tally(*samples).summary())
        assertEquals("none 100%", tally("none", "none").summary())
    }

    @Test
    fun `reset starts a new run`() {
        val tally = tally("pl1", "pl1")
        tally.reset()
        tally.add("none")
        assertEquals(1, tally.samples)
        assertEquals("none 100%", tally.summary())
    }

    @Test
    fun `names beyond the capacity are dropped, the sample still counts`() {
        val tally = ThrottleTally()
        for (index in 0 until ThrottleTally.MAX_REASONS) tally.add("r$index")
        tally.add("one_too_many")
        assertEquals(ThrottleTally.MAX_REASONS + 1, tally.samples)
        check("one_too_many" !in tally.summary())
    }
}
