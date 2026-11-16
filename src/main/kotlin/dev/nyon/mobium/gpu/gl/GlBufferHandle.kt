package dev.nyon.mobium.gpu.gl

/**
 * Renderpearl's OpenGL buffers (`GlBuffer`, through `mixins/GlBufferMixin`): their GL name. 26.4 makes `GlBuffer`
 * package-private, so Module 2 recognises and reads OpenGL buffers through this interface on every version.
 */
interface GlBufferHandle {
    fun `mobium$handle`(): Int
}
