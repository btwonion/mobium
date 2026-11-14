package dev.nyon.mobium.gpu.vk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc

/**
 * `expand_parts.comp` compiles to SPIR-V for Vulkan in every variant Module 2 may ask for (double and integer
 * arithmetic, each with and without each float execution mode), and with glslang's OpenGL rules as the compute shader
 * and as the vertex-stage variant in both arithmetics, so a shader change cannot break a path unnoticed (the GL driver
 * compiles the source itself, at the first frame that needs it). The integer variant must not need 64-bit floats or
 * integers, which the devices it is for lack. Skipped where shaderc's natives are not on the test classpath (they
 * come with Minecraft's libraries on the Fabric target).
 */
class ExpandShaderTest {
    private val source = javaClass.getResourceAsStream("/assets/mobium/shaders/expand_parts.comp")!!.use { String(it.readAllBytes()) }

    private fun shadercAvailable(): Boolean = runCatching {
        Class.forName("org.lwjgl.util.shaderc.Shaderc")
        org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release(org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize())
        true
    }.getOrDefault(false)

    /** The double variant sets each float mode for 32 and 64 bit together (`VulkanExpand.createPipeline`). */
    @Test
    fun `every float-control variant of the double arithmetic compiles to SPIR-V`() {
        assumeTrue(shadercAvailable(), "shaderc natives not available")
        val modes = listOf(
            listOf("MOBIUM_FLOAT_RTE", "MOBIUM_FLOAT_RTE_64"), listOf("MOBIUM_FLOAT_PRESERVE", "MOBIUM_FLOAT_PRESERVE_64"), listOf("MOBIUM_FLOAT_DENORM")
        )
        for (mask in 0 until (1 shl modes.size)) {
            val defines = modes.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.flatten()
            val words = compile(defines)
            assertTrue(Capability.FLOAT64 in capabilities(words), "the double variant uses 64-bit floats ($defines)")
        }
    }

    /**
     * The integer variant sets the modes for 32 bit, the rounding mode for 64 bit as well where the device wants all
     * widths alike. Its SPIR-V declares no capability and no type a device without `shaderFloat64` or `shaderInt64`
     * would refuse.
     */
    @Test
    fun `every float-control variant of the integer arithmetic compiles to SPIR-V without 64-bit types`() {
        assumeTrue(shadercAvailable(), "shaderc natives not available")
        val rounding = listOf(emptyList(), listOf("MOBIUM_FLOAT_RTE"), listOf("MOBIUM_FLOAT_RTE", "MOBIUM_FLOAT_RTE_64"))
        val preserve = listOf(emptyList(), listOf("MOBIUM_FLOAT_PRESERVE"))
        val denormals = listOf(emptyList(), listOf("MOBIUM_FLOAT_DENORM"))
        for (r in rounding) for (p in preserve) for (d in denormals) {
            val defines = listOf("MOBIUM_INTEGER") + r + p + d
            val words = compile(defines)
            val capabilities = capabilities(words)
            assertFalse(Capability.FLOAT64 in capabilities, "Float64 capability with $defines")
            assertFalse(Capability.INT64 in capabilities, "Int64 capability with $defines")
            assertEquals(setOf(32), typeWidths(words), "float and integer widths with $defines")
        }
    }

    @Test
    fun `the OpenGL compute and vertex-stage variants compile in both arithmetics`() {
        assumeTrue(shadercAvailable(), "shaderc natives not available")
        for (arithmetic in listOf(emptyList(), listOf("MOBIUM_INTEGER"))) {
            assertEquals(null, compileForOpenGl(Shaderc.shaderc_compute_shader, arithmetic), "compute $arithmetic")
            assertEquals(null, compileForOpenGl(Shaderc.shaderc_vertex_shader, listOf("MOBIUM_VERTEX") + arithmetic), "vertex stage $arithmetic")
        }
    }

    /** The integer arithmetic sits behind `#ifdef MOBIUM_INTEGER`, the double one behind its `#else`: no `double` outside. */
    @Test
    fun `doubles appear only in the double arithmetic`() {
        val code = source.lines().map { it.substringBefore("//") }
        val start = code.indexOfFirst { it.trim() == "#ifdef MOBIUM_INTEGER" }
        val otherwise = code.withIndex().indexOfFirst { (i, line) -> i > start && line.trim() == "#else" }
        val end = code.withIndex().indexOfFirst { (i, line) -> i > otherwise && line.trim() == "#endif" }
        assertTrue(start in 0 until otherwise && otherwise < end, "#ifdef MOBIUM_INTEGER … #else … #endif")
        val word = Regex("\\bdouble\\b|[0-9]lf\\b")
        val outside = code.filterIndexed { i, line -> (i <= otherwise || i >= end) && word.containsMatchIn(line) }
        assertEquals(emptyList<String>(), outside, "doubles outside the double arithmetic")
        assertTrue(code.subList(otherwise, end).any { word.containsMatchIn(it) }, "the double arithmetic uses doubles")
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

    private object Capability {
        const val FLOAT64 = 10
        const val INT64 = 11
    }

    /** The SPIR-V of the Vulkan variant with [defines], as words. */
    private fun compile(defines: List<String>): IntArray {
        val spirv = VkCompute.compile("expand_parts.comp", source, defines)
        try {
            assertTrue(spirv.remaining() > 0 && spirv.remaining() % 4 == 0, "SPIR-V of $defines")
            assertEquals(0x07230203, spirv.getInt(0), "SPIR-V magic of $defines")
            return IntArray(spirv.remaining() / 4) { spirv.getInt(it * 4) }
        } finally {
            MemoryUtil.memFree(spirv)
        }
    }

    /** Calls [visit] with the opcode and the index of the first operand of every instruction after the 5-word header. */
    private inline fun instructions(words: IntArray, visit: (opcode: Int, operands: Int) -> Unit) {
        var i = 5
        while (i < words.size) {
            val length = words[i] ushr 16
            check(length > 0) { "malformed SPIR-V at word $i" }
            visit(words[i] and 0xFFFF, i + 1)
            i += length
        }
    }

    /** The capabilities the module declares (`OpCapability`, opcode 17). */
    private fun capabilities(words: IntArray): Set<Int> {
        val found = HashSet<Int>()
        instructions(words) { opcode, operands -> if (opcode == 17) found += words[operands] }
        return found
    }

    /** The widths of the module's integer and float types (`OpTypeInt` 21, `OpTypeFloat` 22: result id, width). */
    private fun typeWidths(words: IntArray): Set<Int> {
        val found = HashSet<Int>()
        instructions(words) { opcode, operands -> if (opcode == 21 || opcode == 22) found += words[operands + 1] }
        return found
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
