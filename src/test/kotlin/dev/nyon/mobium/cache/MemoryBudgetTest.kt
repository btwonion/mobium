package dev.nyon.mobium.cache

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoryBudgetTest {
    @Test
    fun `nothing is evicted within the budget`() {
        val budget = MemoryBudget(1000)
        budget.add(1000)
        assertFalse(budget.isOver)
        assertEquals(0, budget.selectEvictions(doubleArrayOf(1.0), longArrayOf(1000), 1).size)
    }

    @Test
    fun `the farthest objects go first, down to the target`() {
        val budget = MemoryBudget(1000)
        budget.add(1200)
        val distances = doubleArrayOf(4.0, 100.0, 1.0, 50.0)
        val bytes = longArrayOf(300, 300, 300, 300)
        // 1200 -> 900 after the farthest (index 1); 900 is the target (90 % of 1000).
        assertArrayEquals(intArrayOf(1), budget.selectEvictions(distances, bytes, 4))
    }

    @Test
    fun `several objects are evicted when one is not enough`() {
        val budget = MemoryBudget(1000)
        budget.add(2000)
        val distances = doubleArrayOf(4.0, 100.0, 1.0, 50.0)
        val bytes = longArrayOf(500, 500, 500, 500)
        assertArrayEquals(intArrayOf(1, 3, 0), budget.selectEvictions(distances, bytes, 4))
    }

    @Test
    fun `only the given count of entries is considered`() {
        val budget = MemoryBudget(100)
        budget.add(300)
        val evicted = budget.selectEvictions(doubleArrayOf(1.0, 2.0, 999.0), longArrayOf(150, 150, 150), 2)
        assertArrayEquals(intArrayOf(1, 0), evicted)
    }

    @Test
    fun `evicted objects stay evicted until usage falls below the resume level`() {
        val budget = MemoryBudget(1000)
        budget.add(900)
        assertTrue(budget.isTight)
        budget.remove(200)
        assertFalse(budget.isTight)
    }

    @Test
    fun `resizing changes what counts as over`() {
        val budget = MemoryBudget(1000)
        budget.add(800)
        budget.resize(500)
        assertTrue(budget.isOver)
    }
}
