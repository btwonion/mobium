package dev.nyon.mobium.gpu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RingAllocatorTest {
    @Test
    fun `ranges are aligned and packed`() {
        val ring = RingAllocator(1024, alignment = 256)
        assertEquals(0, ring.allocate(80))
        assertEquals(256, ring.allocate(256))
        assertEquals(512, ring.allocate(1))
        assertEquals(513, ring.used)
    }

    @Test
    fun `a full slot asks to grow by doubling`() {
        val ring = RingAllocator(1024, alignment = 256)
        assertEquals(0, ring.allocate(1000))
        assertEquals(-1, ring.allocate(100))
        assertEquals(2048, ring.capacityFor(100))
        assertEquals(8192, ring.capacityFor(5000))
        ring.grow(2048)
        assertEquals(1024, ring.allocate(100))
        assertEquals(2048, ring.capacity())
    }

    @Test
    fun `slots rotate, start empty and keep their own capacity`() {
        val ring = RingAllocator(512, alignment = 16, slots = 3)
        ring.allocate(500)
        ring.grow(1024)
        assertEquals(1, ring.nextFrame())
        assertEquals(0, ring.used)
        assertEquals(512, ring.capacity())
        assertEquals(2, ring.nextFrame())
        assertEquals(0, ring.nextFrame())
        assertEquals(1024, ring.capacity())
        assertEquals(0, ring.allocate(1024))
    }

    @Test
    fun `an exact fit does not grow`() {
        val ring = RingAllocator(256, alignment = 256)
        assertEquals(0, ring.allocate(256))
        assertEquals(256, ring.capacityFor(0))
    }

    @Test
    fun `invalid arguments are rejected`() {
        assertThrows<IllegalArgumentException> { RingAllocator(0) }
        assertThrows<IllegalArgumentException> { RingAllocator(1024, alignment = 100) }
        assertThrows<IllegalArgumentException> { RingAllocator(1024).allocate(-1) }
        val ring = RingAllocator(1024)
        assertThrows<IllegalArgumentException> { ring.grow(512) }
    }
}
