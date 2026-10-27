package dev.nyon.mobium.core

import com.mojang.blaze3d.systems.RenderSystem
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL43C

/** Which Renderpearl backend the game runs on, from `DeviceInfo.backendName()` ("OpenGL" or "Vulkan" in 26.3). */
enum class GraphicsBackend {
    OPENGL, VULKAN, OTHER;

    companion object {
        fun fromName(name: String): GraphicsBackend = when (name) {
            "OpenGL" -> OPENGL
            "Vulkan" -> VULKAN
            else -> OTHER
        }
    }
}

/**
 * What the GPU and the active backend can do, as far as the modules care.
 *
 * @param glCompute is true when the OpenGL context has compute shaders and SSBOs (core 4.3 or both ARB extensions)
 * and double precision in shaders (core 4.0), which Module 2's expand pass uses for correctly rounded square roots and
 * divisions. Always false on Vulkan until Mobium has a Vulkan compute path (PLAN.md M7).
 * @param glVertexStores is true when, on top of [glCompute], vertex shaders may write three shader storage buffers
 * (`GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS`, which OpenGL lets be 0), so that the expand pass can run as a point draw.
 * @param multiDrawIndirect is Renderpearl's own `DeviceFeatures.multiDrawIndirect()`.
 */
class Capabilities(
    @JvmField val backend: GraphicsBackend,
    @JvmField val backendName: String,
    @JvmField val deviceName: String,
    @JvmField val vendor: String,
    @JvmField val driverInfo: String,
    @JvmField val glCompute: Boolean,
    @JvmField val glVertexStores: Boolean,
    @JvmField val multiDrawIndirect: Boolean
) {
    val compute: Boolean
        get() = glCompute

    fun describe(): String =
        "$backendName on $deviceName ($vendor, $driverInfo); compute: ${yesNo(compute)}, vertex-stage stores: " +
            "${yesNo(glVertexStores)}, multi-draw indirect: ${yesNo(multiDrawIndirect)}"

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    companion object {
        @JvmStatic
        var current: Capabilities? = null
            private set

        /** Must run on the render thread after the GPU device has been created. */
        fun detect(): Capabilities {
            val info = RenderSystem.getDevice().deviceInfo
            val backend = GraphicsBackend.fromName(info.backendName())
            val glCompute = backend == GraphicsBackend.OPENGL && hasGlCompute()
            val capabilities = Capabilities(
                backend,
                info.backendName(),
                info.name(),
                info.vendorName(),
                info.driverInfo(),
                glCompute,
                glCompute && hasGlVertexStores(),
                info.features().multiDrawIndirect()
            )
            current = capabilities
            return capabilities
        }

        // Renderpearl creates the LWJGL capabilities on the render thread (GlDevice constructor), so they are current here.
        private fun hasGlCompute(): Boolean = runCatching {
            val caps = GL.getCapabilities()
            (caps.OpenGL43 || (caps.GL_ARB_compute_shader && caps.GL_ARB_shader_storage_buffer_object)) &&
                (caps.OpenGL40 || caps.GL_ARB_gpu_shader_fp64)
        }.getOrDefault(false)

        private fun hasGlVertexStores(): Boolean = runCatching {
            GL11C.glGetInteger(GL43C.GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS) >= 3
        }.getOrDefault(false)
    }
}
