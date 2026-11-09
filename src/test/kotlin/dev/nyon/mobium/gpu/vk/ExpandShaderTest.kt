package dev.nyon.mobium.gpu.vk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc

/**
 * `expand_parts.comp` compiles to SPIR-V for Vulkan in every variant Module 2 may ask for (with and without each float
 * execution mode), and with glslang's OpenGL rules as the compute shader and as the vertex-stage variant, so a shader
 * change cannot break either path unnoticed (the GL driver compiles the source itself, at the first frame that needs
 * it). Skipped where shaderc's natives are not on the test classpath (they come with Minecraft's libraries on the
 * Fabric target).
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

    @Test
    fun `the OpenGL compute and vertex-stage variants compile`() {
        assumeTrue(shadercAvailable(), "shaderc natives not available")
        assertEquals(null, compileForOpenGl(Shaderc.shaderc_compute_shader, emptyList()), "compute")
        assertEquals(null, compileForOpenGl(Shaderc.shaderc_vertex_shader, listOf("MOBIUM_VERTEX")), "vertex stage")
    }

    /**
     * GLSL 4.60 reserves these words for future use (section 3.6), and Mesa's compiler also refuses `packed` as a name,
     * which glslang accepts: a name from this list compiles in the tests above and fails in the driver.
     */
    @Test
    fun `no name is a word GLSL or Mesa reserves`() {
        val reserved = (
            "common partition active asm class union enum typedef template this resource goto inline noinline public " +
                "static extern external interface long short half fixed unsigned superp input output hvec2 hvec3 hvec4 " +
                "fvec2 fvec3 fvec4 sampler3DRect filter sizeof cast namespace using packed"
            ).split(' ').toSet()
        val code = source.lines().joinToString("\n") { it.substringBefore("//") }
        val used = Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(code).map { it.value }.filter { it in reserved }.toSet()
        assertEquals(emptySet<String>(), used, "reserved words used as names")
    }

    /** glslang's verdict on the shader as OpenGL 4.5 would read it: null if it compiles, else its errors. */
    private fun compileForOpenGl(kind: Int, defines: List<String>): String? {
        val compiler = Shaderc.shaderc_compiler_initialize()
        val options = Shaderc.shaderc_compile_options_initialize()
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_opengl, Shaderc.shaderc_env_version_opengl_4_5)
            for (define in defines) Shaderc.shaderc_compile_options_add_macro_definition(options, define, "1")
            val result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, "expand_parts.comp", "main", options)
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) == Shaderc.shaderc_compilation_status_success) return null
                return Shaderc.shaderc_result_get_error_message(result)
            } finally {
                Shaderc.shaderc_result_release(result)
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options)
            Shaderc.shaderc_compiler_release(compiler)
        }
    }
}
