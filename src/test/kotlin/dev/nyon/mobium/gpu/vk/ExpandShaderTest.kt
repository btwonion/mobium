package dev.nyon.mobium.gpu.vk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.lwjgl.system.MemoryUtil

/**
 * `expand_parts.comp` compiles to SPIR-V for Vulkan in every variant Module 2 may ask for (with and without each float
 * execution mode), so a shader change cannot break the Vulkan path unnoticed. Skipped where shaderc's natives are not
 * on the test classpath (they come with Minecraft's libraries on the Fabric target).
 */
class ExpandShaderTest {
    private val source = javaClass.getResourceAsStream("/assets/mobium/shaders/expand_parts.comp")!!.use { String(it.readAllBytes()) }

    private fun shadercAvailable(): Boolean = runCatching {
        Class.forName("org.lwjgl.util.shaderc.Shaderc")
        org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release(org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize())
        true
    }.getOrDefault(false)

    @Test
    fun `every float-control variant compiles to SPIR-V`() {
        assumeTrue(shadercAvailable(), "shaderc natives not available")
        val modes = listOf("MOBIUM_FLOAT_RTE", "MOBIUM_FLOAT_PRESERVE", "MOBIUM_FLOAT_DENORM")
        for (mask in 0 until (1 shl modes.size)) {
            val defines = modes.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            val spirv = VkCompute.compile("expand_parts.comp", source, defines)
            try {
                assertTrue(spirv.remaining() > 0 && spirv.remaining() % 4 == 0, "SPIR-V of $defines")
                assertEquals(0x07230203, spirv.getInt(0), "SPIR-V magic of $defines")
            } finally {
                MemoryUtil.memFree(spirv)
            }
        }
    }
}
