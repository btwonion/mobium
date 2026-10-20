package dev.nyon.mobium.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FailureLedgerTest {
    @Test
    fun `only the first failure of a module is reported`() {
        val ledger = FailureLedger()
        assertTrue(ledger.moduleFailed(Module.STATIC_CACHE, "first"))
        assertFalse(ledger.moduleFailed(Module.STATIC_CACHE, "second"))
        assertTrue(ledger.moduleFailed(Module.PART_TRANSFORM, "other module"))
        assertEquals(mapOf(Module.STATIC_CACHE to "first", Module.PART_TRANSFORM to "other module"), ledger.failures)
    }

    @Test
    fun `a type goes vanilla after the limit, once`() {
        val ledger = FailureLedger()
        repeat(FailureLedger.TYPE_FAILURE_LIMIT - 1) { assertFalse(ledger.typeFailed(Module.STATIC_CACHE, "chests")) }
        assertFalse(ledger.isTypeDisabled(Module.STATIC_CACHE, "chests"))
        assertTrue(ledger.typeFailed(Module.STATIC_CACHE, "chests"))
        assertTrue(ledger.isTypeDisabled(Module.STATIC_CACHE, "chests"))
        assertFalse(ledger.typeFailed(Module.STATIC_CACHE, "chests"))
        assertFalse(ledger.isTypeDisabled(Module.STATIC_CACHE, "signs"))
        assertEquals(listOf("static_cache/chests"), ledger.disabledTypes())
    }

    @Test
    fun `clear forgets everything`() {
        val ledger = FailureLedger()
        ledger.moduleFailed(Module.ANIMATION_LOD, "x")
        repeat(FailureLedger.TYPE_FAILURE_LIMIT) { ledger.typeFailed(Module.STATIC_CACHE, "signs") }
        ledger.clear()
        assertTrue(ledger.failures.isEmpty())
        assertFalse(ledger.isTypeDisabled(Module.STATIC_CACHE, "signs"))
    }
}
