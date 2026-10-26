package dev.nyon.mobium.parts

import dev.nyon.mobium.gpu.gl.GlCompute
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL20C
import org.lwjgl.opengl.GL30C
import org.lwjgl.opengl.GL42C
import org.lwjgl.opengl.GL43C

/**
 * The GPU half of Module 2 (OpenGL 4.3): uploads new meshes and one staged buffer's records, then runs
 * `expand_parts.comp` once per quad, writing the reserved vertices into the vertex buffer vanilla just uploaded,
 * followed by a vertex-attribute barrier so that vanilla's draws read them. Runs between `upload()` and the first draw
 * (docs/vanilla-notes.md §16). Render thread only.
 */
object ExpandPass {
    private const val SHADER = "/assets/mobium/shaders/expand_parts.comp"
    private const val LOCAL_SIZE = 64
    private const val MAX_GROUPS = 65535
    private const val INITIAL_MESH_BYTES = 1L shl 20
    private const val MESH_QUAD_BYTES = MeshPool.QUAD_WORDS * 4L

    private var program = 0
    private var meshBuffer = 0
    private var meshCapacity = 0L
    private var frameBuffer = 0

    /** GPU memory of the pass's own buffers (meshes and the last frame's records), for F3. */
    var bytesAllocated = 0L
        private set

    /** Expands [batch] into the vertex buffer with GL name [vertexBuffer]. */
    fun dispatch(batch: PartBatch, vertexBuffer: Int) {
        if (program == 0) program = GlCompute.createProgram("expand_parts.comp", source())
        uploadMeshes()

        val cubeBytes = batch.cubeCount.toLong() * PartBatch.CUBE_BYTES
        val partBytes = batch.partCount.toLong() * PartBatch.PART_BYTES
        val slotBytes = batch.draws.size * 4L
        val frameBytes = cubeBytes + partBytes + slotBytes
        if (frameBuffer == 0) frameBuffer = GlCompute.createBuffer(frameBytes)
        GlCompute.orphan(frameBuffer, frameBytes)
        GlCompute.upload(frameBuffer, 0, cubeBytes, batch.cubesAddress)
        GlCompute.upload(frameBuffer, cubeBytes, partBytes, batch.partsAddress)
        GlCompute.upload(frameBuffer, cubeBytes + partBytes, slotBytes, batch.slotsAddress)
        bytesAllocated = meshCapacity + frameBytes

        val previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM)
        GL20C.glUseProgram(program)
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, meshBuffer)
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, frameBuffer)
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, vertexBuffer)
        GL30C.glUniform1ui(0, batch.quadCount)
        GL30C.glUniform1ui(1, batch.cubeCount)
        GL30C.glUniform1ui(2, 0)
        GL30C.glUniform1ui(3, (cubeBytes / 4).toInt())
        GL30C.glUniform1ui(4, ((cubeBytes + partBytes) / 4).toInt())
        var quadBase = 0
        while (quadBase < batch.quadCount) {
            val groups = minOf((batch.quadCount - quadBase + LOCAL_SIZE - 1) / LOCAL_SIZE, MAX_GROUPS)
            GL30C.glUniform1ui(5, quadBase)
            GL43C.glDispatchCompute(groups, 1, 1)
            quadBase += groups * LOCAL_SIZE
        }
        GL42C.glMemoryBarrier(GL42C.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT)
        for (binding in 0..2) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, 0)
        GL20C.glUseProgram(previousProgram)
    }

    /** Uploads the quads [MeshPool] wrote since the last dispatch, growing the mesh buffer when needed. */
    private fun uploadMeshes() {
        val needed = MeshPool.quadCount * MESH_QUAD_BYTES
        if (needed > meshCapacity) {
            var capacity = maxOf(meshCapacity, INITIAL_MESH_BYTES)
            while (capacity < needed) capacity *= 2
            val grown = GlCompute.createBuffer(capacity)
            if (meshBuffer != 0) {
                GlCompute.copy(meshBuffer, grown, MeshPool.uploadedQuads * MESH_QUAD_BYTES)
                GlCompute.deleteBuffer(meshBuffer)
            }
            meshBuffer = grown
            meshCapacity = capacity
        }
        val pendingQuads = MeshPool.quadCount - MeshPool.uploadedQuads
        if (pendingQuads > 0) {
            GlCompute.upload(meshBuffer, MeshPool.uploadedQuads * MESH_QUAD_BYTES, pendingQuads * MESH_QUAD_BYTES, MeshPool.pendingAddress)
            MeshPool.uploaded()
        }
    }

    private fun source(): String = ExpandPass::class.java.getResourceAsStream(SHADER)?.use { String(it.readAllBytes()) }
        ?: throw IllegalStateException("$SHADER is missing")
}
