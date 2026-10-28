package dev.nyon.mobium.parts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PathSamplerTest {
    @Test
    fun `a frame takes at most perFrame parts of a path`() {
        val sampler = PathSampler(PATHS, PER_PATH, PER_FRAME)
        repeat(PER_FRAME) { assertTrue(sampler.take(1)) }
        assertFalse(sampler.take(1))
        assertEquals(PER_FRAME, sampler.total)
    }

    @Test
    fun `paths are counted apart`() {
        val sampler = PathSampler(PATHS, PER_PATH, PER_FRAME)
        repeat(PER_FRAME) { sampler.take(1) }
        assertTrue(sampler.take(2))
        assertEquals(2, sampler.pathsSeen)
    }

    @Test
    fun `a path stops after perPath parts over several frames`() {
        val sampler = PathSampler(PATHS, PER_PATH, PER_FRAME)
        var taken = 0
        repeat(PER_PATH) {
            while (sampler.take(3)) taken++
            sampler.endFrame()
        }
        assertEquals(PER_PATH, taken)
        assertFalse(sampler.take(3))
        assertEquals(1, sampler.pathsSeen)
    }

    @Test
    fun `paths outside the range are never taken`() {
        val sampler = PathSampler(PATHS, PER_PATH, PER_FRAME)
        assertFalse(sampler.take(-1))
        assertFalse(sampler.take(PATHS))
        assertEquals(0, sampler.total)
    }

    @Test
    fun `reset starts every path over`() {
        val sampler = PathSampler(PATHS, PER_PATH, PER_FRAME)
        repeat(PER_PATH) {
            while (sampler.take(0)) Unit
            sampler.endFrame()
        }
        sampler.reset()
        assertEquals(0, sampler.total)
        assertTrue(sampler.take(0))
    }

    private companion object {
        const val PATHS = 8
        const val PER_PATH = 6
        const val PER_FRAME = 2
    }
}
