package dev.nyon.mobium.core

import dev.nyon.mobium.config.CacheConfig
import dev.nyon.mobium.config.Config
import dev.nyon.mobium.config.LodConfig
import dev.nyon.mobium.config.LodPreset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ModuleRulesTest {
    private val prototypeOn = Config(cache = CacheConfig(prototype = true))
    private val openGl = ModuleEnvironment(GraphicsBackend.OPENGL, compute = true, shaderPack = false, abOff = false)

    private fun reason(module: Module, config: Config = prototypeOn, environment: ModuleEnvironment = openGl) =
        ModuleRules.resolve(module, config, environment).reason

    @Test
    fun `static cache runs on OpenGL with the prototype enabled`() {
        assertNull(reason(Module.STATIC_CACHE))
        assertEquals(OffReason.PROTOTYPE_OFF, reason(Module.STATIC_CACHE, Config()))
    }

    @Test
    fun `modules that do not exist yet say so`() {
        assertEquals(OffReason.NOT_BUILT, reason(Module.PART_TRANSFORM))
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
        assertEquals(OffReason.GLOBAL_SWITCH, reason(Module.STATIC_CACHE, prototypeOn.copy(enabled = false), abOff))
        assertEquals(OffReason.AB_TOGGLE, reason(Module.STATIC_CACHE, prototypeOn, abOff))
        val cacheOff = prototypeOn.deepCopy().also { it.cache.enabled = false }
        assertEquals(OffReason.CONFIG, reason(Module.STATIC_CACHE, cacheOff))
    }

    @Test
    fun `an overlapping mod names itself`() {
        val overlap = openGl.copy(overlaps = mapOf(Module.STATIC_CACHE to "Enhanced Block Entities"))
        val status = ModuleRules.resolve(Module.STATIC_CACHE, prototypeOn, overlap)
        assertEquals(OffReason.OVERLAP, status.reason)
        assertEquals("Enhanced Block Entities", status.detail)
        assertEquals(OffReason.NOT_BUILT, reason(Module.PART_TRANSFORM, environment = overlap))
    }

    @Test
    fun `Vulkan, missing compute and shader packs keep modules on vanilla`() {
        assertEquals(OffReason.BACKEND, reason(Module.STATIC_CACHE, environment = openGl.copy(backend = GraphicsBackend.VULKAN)))
        assertEquals(OffReason.BACKEND, reason(Module.STATIC_CACHE, environment = openGl.copy(backend = null)))
        assertEquals(OffReason.NO_COMPUTE, reason(Module.PART_TRANSFORM, environment = openGl.copy(compute = false)))
        assertEquals(OffReason.SHADER_PACK, reason(Module.STATIC_CACHE, environment = openGl.copy(shaderPack = true)))
    }

    @Test
    fun `every module is resolved`() {
        assertEquals(Module.entries.toSet(), ModuleRules.resolve(Config(), openGl).keys)
    }
}
