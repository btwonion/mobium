package dev.nyon.mobium.gpu.gl

import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL20C
import org.lwjgl.opengl.GL31C
import org.lwjgl.opengl.GL43C

/**
 * Raw OpenGL 4.3 for Module 2's compute pass, which Renderpearl has no API for (docs/vanilla-notes.md §5, §16):
 * programs, shader storage buffers and their uploads. Renderpearl's GL backend does not cache the bound program or the
 * storage buffer bindings, and binds its pipeline anew in every render pass, so a dispatch between passes only has to
 * put back the program and unbind what it bound. Render thread only, with the game's GL context current.
 */
object GlCompute {
    /** Compiles and links a compute program; throws with the driver's log if that fails. */
    fun createProgram(name: String, source: String): Int {
        val shader = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER)
        GL20C.glShaderSource(shader, source)
        GL20C.glCompileShader(shader)
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL20C.GL_FALSE) {
            val log = GL20C.glGetShaderInfoLog(shader)
            GL20C.glDeleteShader(shader)
            throw IllegalStateException("$name does not compile: $log")
        }
        val program = GL20C.glCreateProgram()
        GL20C.glAttachShader(program, shader)
        GL20C.glLinkProgram(program)
        GL20C.glDetachShader(program, shader)
        GL20C.glDeleteShader(shader)
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL20C.GL_FALSE) {
            val log = GL20C.glGetProgramInfoLog(program)
            GL20C.glDeleteProgram(program)
            throw IllegalStateException("$name does not link: $log")
        }
        return program
    }

    /** A new storage buffer of [bytes] bytes whose contents the CPU replaces ([upload]). */
    fun createBuffer(bytes: Long): Int {
        val buffer = GL15C.glGenBuffers()
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer)
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, bytes, GL15C.GL_DYNAMIC_DRAW)
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0)
        return buffer
    }

    /** Gives [buffer] fresh storage of [bytes] bytes (orphaning the old, which the GPU may still read). */
    fun orphan(buffer: Int, bytes: Long) {
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer)
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, bytes, GL15C.GL_STREAM_DRAW)
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0)
    }

    /** Copies [bytes] bytes at [address] into [buffer] at [offset]. */
    fun upload(buffer: Int, offset: Long, bytes: Long, address: Long) {
        if (bytes == 0L) return
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer)
        GL15C.nglBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, offset, bytes, address)
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0)
    }

    /** Copies the first [bytes] bytes of [source] into [target] on the GPU. */
    fun copy(source: Int, target: Int, bytes: Long) {
        if (bytes == 0L) return
        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, source)
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, target)
        GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0, 0, bytes)
        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, 0)
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0)
    }

    /** Reads [bytes] bytes of [buffer] at [offset] into [address], waiting for the GPU (dev validation only). */
    fun read(buffer: Int, offset: Long, bytes: Long, address: Long) {
        GL43C.glMemoryBarrier(GL43C.GL_BUFFER_UPDATE_BARRIER_BIT)
        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, buffer)
        GL15C.nglGetBufferSubData(GL31C.GL_COPY_READ_BUFFER, offset, bytes, address)
        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, 0)
    }

    fun deleteBuffer(buffer: Int) {
        if (buffer != 0) GL15C.glDeleteBuffers(buffer)
    }
}
