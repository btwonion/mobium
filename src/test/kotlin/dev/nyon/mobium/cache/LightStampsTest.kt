package dev.nyon.mobium.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LightStampsTest {
    @Test
    fun `a section without updates never changed`() {
        val stamps = LightStamps()
        assertFalse(stamps.changedSince(SECTION, 0L))
    }

    @Test
    fun `an update after the check is a change, one before it is not`() {
        val stamps = LightStamps()
        stamps.update(SECTION)
        val checked = stamps.epoch
        assertFalse(stamps.changedSince(SECTION, checked))
        stamps.update(SECTION)
        assertTrue(stamps.changedSince(SECTION, checked))
    }

    @Test
    fun `an update elsewhere does not change the section`() {
        val stamps = LightStamps()
        val checked = stamps.epoch
        stamps.update(OTHER)
        assertTrue(stamps.epoch > checked)
        assertFalse(stamps.changedSince(SECTION, checked))
        assertTrue(stamps.changedSince(OTHER, checked))
    }

    @Test
    fun `pruning keeps the sections someone has not seen yet`() {
        val stamps = LightStamps()
        stamps.update(SECTION)
        val checked = stamps.epoch
        stamps.update(OTHER)
        stamps.prune(checked)
        assertEquals(1, stamps.size)
        assertTrue(stamps.changedSince(OTHER, checked))
    }

    @Test
    fun `a pruned section that is updated again still counts as changed`() {
        // The case a per-section counter gets wrong after pruning: it restarts at 0 and can reach the stored value again.
        val stamps = LightStamps()
        stamps.update(SECTION)
        stamps.update(SECTION)
        val checked = stamps.epoch
        stamps.prune(checked)
        assertEquals(0, stamps.size)
        stamps.update(SECTION)
        stamps.update(SECTION)
        assertTrue(stamps.changedSince(SECTION, checked))
    }

    @Test
    fun `clearing keeps the epoch counting`() {
        val stamps = LightStamps()
        stamps.update(SECTION)
        val before = stamps.epoch
        stamps.clear()
        stamps.update(SECTION)
        assertTrue(stamps.epoch > before)
        assertTrue(stamps.changedSince(SECTION, before))
    }

    private companion object {
        const val SECTION = 42L
        const val OTHER = 43L
    }
}
