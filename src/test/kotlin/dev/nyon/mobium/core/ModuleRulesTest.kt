package dev.nyon.mobium.core

import dev.nyon.mobium.config.CacheConfig
import dev.nyon.mobium.config.Config
import dev.nyon.mobium.config.LodConfig
import dev.nyon.mobium.config.LodPreset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ModuleRulesTest {
    private val defaults = Config()
    private val allOn = Config(lod = LodConfig(preset = LodPreset.SUBTLE))
    private val openGl = ModuleEnvironment(GraphicsBackend.OPENGL, compute = true, shaderPack = false, abOff = false)

    private fun reason(module: Module, config: Config = defaults, environment: ModuleEnvironment = openGl) =
        ModuleRules.resolve(module, config, environment).reason

    @Test
    fun `static cache runs on OpenGL by default, with or without a shader pack`() {
        assertNull(reason(Module.STATIC_CACHE))
        assertNull(reason(Module.STATIC_CACHE, environment = openGl.copy(shaderPack = true)))
    }

    @Test
    fun `part transform runs on OpenGL with compute, with or without a shader pack`() {
        assertNull(reason(Module.PART_TRANSFORM))
        assertNull(reason(Module.PART_TRANSFORM, environment = openGl.copy(shaderPack = true)))
    }

    @Test
    fun `modules that do not exist yet say so`() {
        assertEquals(OffReason.NOT_BUILT, reason(Module.ANIMATION_LOD, Config(lod = LodConfig(preset = LodPreset.SUBTLE))))
    }

    @Test
    fun `animation LOD is off by default`() {
        assertEquals(OffReason.CONFIG, reason(Module.ANIMATION_LOD, Config()))
    }

    @Test
    fun `a failure outranks everything else`() {
        val failed = openGl.copy(abOff = true, failures = mapOf(Module.STATIC_CACHE to "boom"))
        val status = ModuleRules.resolve(Module.STATIC_CACHE, Config(enabled = false), failed)
        assertEquals(OffReason.FAILED, status.reason)
        assertEquals("boom", status.detail)
    }

    @Test
    fun `global switch, A-B key and module switch come in that order`() {
        val abOff = openGl.copy(abOff = true)
        assertEquals(OffReason.GLOBAL_SWITCH, reason(Module.STATIC_CACHE, defaults.copy(enabled = false), abOff))
        assertEquals(OffReason.AB_TOGGLE, reason(Module.STATIC_CACHE, defaults, abOff))
        val cacheOff = defaults.deepCopy().also { it.cache.enabled = false }
        assertEquals(OffReason.CONFIG, reason(Module.STATIC_CACHE, cacheOff))
    }

    @Test
    fun `an overlapping mod names itself`() {
        val overlap = openGl.copy(overlaps = mapOf(Module.STATIC_CACHE to "Enhanced Block Entities"))
        val status = ModuleRules.resolve(Module.STATIC_CACHE, defaults, overlap)
        assertEquals(OffReason.OVERLAP, status.reason)
        assertEquals("Enhanced Block Entities", status.detail)
        assertNull(reason(Module.PART_TRANSFORM, environment = overlap))
    }

    @Test
    fun `an untested mod keeps its modules on vanilla, after an overlap and before the backend`() {
        val vitrail = openGl.copy(
            backend = GraphicsBackend.VULKAN,
            untested = mapOf(Module.STATIC_CACHE to "Vitrail", Module.PART_TRANSFORM to "Vitrail")
        )
        val status = ModuleRules.resolve(Module.STATIC_CACHE, defaults, vitrail)
        assertEquals(OffReason.UNTESTED, status.reason)
        assertEquals("Vitrail", status.detail)
        assertEquals(OffReason.UNTESTED, reason(Module.PART_TRANSFORM, environment = vitrail))
        assertEquals(OffReason.OVERLAP, reason(Module.STATIC_CACHE, environment = vitrail.copy(overlaps = mapOf(Module.STATIC_CACHE to "Vanillate"))))
        assertEquals(OffReason.CONFIG, reason(Module.STATIC_CACHE, Config(cache = CacheConfig(enabled = false)), vitrail))
    }

    @Test
    fun `the static cache runs on OpenGL and Vulkan, Module 2 on OpenGL only so far`() {
        val vulkan = openGl.copy(backend = GraphicsBackend.VULKAN)
        assertNull(reason(Module.STATIC_CACHE, environment = vulkan))
        assertEquals(OffReason.BACKEND, reason(Module.PART_TRANSFORM, environment = vulkan))
        assertEquals("vulkan", ModuleRules.resolve(Module.PART_TRANSFORM, Config(), vulkan).detail)
        assertEquals(OffReason.NOT_BUILT, reason(Module.ANIMATION_LOD, allOn, vulkan))
    }

    @Test
    fun `other backends, no device yet and missing compute keep modules on vanilla`() {
        for (module in Module.entries) {
            assertEquals(OffReason.BACKEND, reason(module, allOn, openGl.copy(backend = GraphicsBackend.OTHER)))
            assertEquals(OffReason.BACKEND, reason(module, allOn, openGl.copy(backend = null)))
        }
        assertEquals(OffReason.NO_COMPUTE, reason(Module.PART_TRANSFORM, environment = openGl.copy(compute = false)))
    }

    @Test
    fun `every module is resolved`() {
        assertEquals(Module.entries.toSet(), ModuleRules.resolve(Config(), openGl).keys)
    }
}
