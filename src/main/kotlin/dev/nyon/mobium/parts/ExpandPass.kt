package dev.nyon.mobium.parts

import dev.nyon.mobium.core.Capabilities
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.CompatMod
import dev.nyon.mobium.core.IrisCompat
import dev.nyon.mobium.debug.ExpandProfile
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
 *
 * The shader runs as a compute dispatch ([STAGE_COMPUTE]) or, compiled as a vertex shader, as a point draw with
 * rasterisation off ([STAGE_VERTEX]), which keeps it in the graphics pipeline: Mesa's iris records a dispatch in a
 * batch of its own and flushes both batches around it, about 0.2 ms of GPU time per frame however few parts there are
 * (docs/vanilla-notes.md §16). Both write the same bytes; [stage] picks one per pass.
 */
object ExpandPass {
    private const val SHADER = "/assets/mobium/shaders/expand_parts.comp"
    private const val LOCAL_SIZE = 64
    private const val MAX_GROUPS = 65535
    private const val INITIAL_MESH_BYTES = 1L shl 20
    private const val MESH_QUAD_BYTES = MeshPool.QUAD_WORDS * 4L

    const val STAGE_COMPUTE = 0
    const val STAGE_VERTEX = 1

    /** Vertex shaders can write the pass's storage buffers on this GPU (`Capabilities.glVertexStores`). */
    var vertexStores = false
        private set

    /** The stage from `-Dmobium.parts.stage=compute|vertex`, or -1 for the automatic choice ([stage]). */
    private var configuredStage = -1

    /** A stage set for a comparison (`/mobium stages`, the self-check, the gametests), or -1. */
    @JvmField
    var forcedStage = -1

    /**
     * The stage the next pass uses: the vertex stage where vertex shaders can write storage buffers and no shader pack
     * is in use. With a shader pack, compute: there the frame's GPU work is large, and the batch flushes that a
     * dispatch causes on iris submit the frame's first part early, which made frames faster in the dev client than
     * the vertex stage's lower cost (docs/benchmarks.md *Module 2's fixed cost*).
     */
    val stage: Int
        get() = when {
            forcedStage >= 0 -> forcedStage
            configuredStage >= 0 -> configuredStage
            vertexStores && !(Compat.isLoaded(CompatMod.IRIS) && IrisCompat.isShaderPackInUse()) -> STAGE_VERTEX
            else -> STAGE_COMPUTE
        }

    /** How [stage] is chosen, for the log. */
    fun describeChoice(): String = when {
        configuredStage == STAGE_COMPUTE -> "compute (-Dmobium.parts.stage)"
        configuredStage == STAGE_VERTEX -> "vertex stage (-Dmobium.parts.stage)"
        vertexStores -> "vertex stage, compute while a shader pack is in use"
        else -> "compute (vertex shaders cannot write storage buffers on this GPU)"
    }

    /** The stage of the last pass, or -1 before the first ([PartCheck] samples parts per stage). */
    @JvmField
    var lastStage = -1

    /** Name of [stage], for F3 and the log. */
    val stageName: String
        get() = stageName(stage)

    fun stageName(stage: Int): String = if (stage == STAGE_VERTEX) "vertex stage" else "compute"

    private var computeProgram = 0
    private var vertexProgram = 0
    private var emptyVertexArray = 0
    private var meshBuffer = 0
    private var meshCapacity = 0L
    private var frameBuffer = 0

    /** GPU memory of the pass's own buffers (meshes and the last frame's records), for F3. */
    var bytesAllocated = 0L
        private set

    /**
     * Reads what the GPU can do and `-Dmobium.parts.stage` (`compute` or `vertex`, for comparisons and bug reports;
     * `vertex` only where the GPU has it); called once the GPU is known.
     */
    fun configure(capabilities: Capabilities) {
        vertexStores = capabilities.glVertexStores
        configuredStage = when (System.getProperty("mobium.parts.stage")) {
            "compute" -> STAGE_COMPUTE
            "vertex" -> if (vertexStores) STAGE_VERTEX else -1
            else -> -1
        }
    }

    /** Expands [batch] into the vertex buffer with GL name [vertexBuffer]. */
    fun dispatch(batch: PartBatch, vertexBuffer: Int) {
        val profiling = ExpandProfile.enabled
        var lap = if (profiling) System.nanoTime() else 0L
        val stage = stage
        lastStage = stage
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
        if (profiling) {
            lap = ExpandProfile.lap(ExpandProfile.RECORDS, lap)
            ExpandProfile.passes++
        }

        if (profiling) ExpandProfile.passStart()
        val previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM)
        GL20C.glUseProgram(program(stage))
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, meshBuffer)
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, frameBuffer)
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, vertexBuffer)
        GL30C.glUniform1ui(0, batch.quadCount)
        GL30C.glUniform1ui(1, batch.cubeCount)
        GL30C.glUniform1ui(2, 0)
        GL30C.glUniform1ui(3, (cubeBytes / 4).toInt())
        GL30C.glUniform1ui(4, ((cubeBytes + partBytes) / 4).toInt())
        if (profiling) lap = ExpandProfile.lap(ExpandProfile.STATE, lap)
        if (stage == STAGE_VERTEX) {
            if (emptyVertexArray == 0) emptyVertexArray = GL30C.glGenVertexArrays()
            val previousVertexArray = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING)
            GL30C.glUniform1ui(5, 0)
            GL30C.glBindVertexArray(emptyVertexArray)
            GL11C.glEnable(GL30C.GL_RASTERIZER_DISCARD)
            GL11C.glDrawArrays(GL11C.GL_POINTS, 0, batch.quadCount)
            GL11C.glDisable(GL30C.GL_RASTERIZER_DISCARD)
            GL30C.glBindVertexArray(previousVertexArray)
        } else {
            var quadBase = 0
            while (quadBase < batch.quadCount) {
                val groups = minOf((batch.quadCount - quadBase + LOCAL_SIZE - 1) / LOCAL_SIZE, MAX_GROUPS)
                GL30C.glUniform1ui(5, quadBase)
                GL43C.glDispatchCompute(groups, 1, 1)
                quadBase += groups * LOCAL_SIZE
            }
        }
        if (profiling) lap = ExpandProfile.lap(ExpandProfile.RUN, lap)
        GL42C.glMemoryBarrier(GL42C.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT)
        for (binding in 0..2) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, 0)
        GL20C.glUseProgram(previousProgram)
        if (profiling) {
            ExpandProfile.passEnd()
            ExpandProfile.lap(ExpandProfile.BARRIER, lap)
        }
    }

    private fun program(stage: Int): Int = if (stage == STAGE_VERTEX) {
        if (vertexProgram == 0) vertexProgram = GlCompute.createProgram("expand_parts.comp (vertex)", source("MOBIUM_VERTEX"), GL20C.GL_VERTEX_SHADER)
        vertexProgram
    } else {
        if (computeProgram == 0) computeProgram = GlCompute.createProgram("expand_parts.comp", source(null))
        computeProgram
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

    /** The shader's source, with `#define [define]` after its `#version` line if given. */
    private fun source(define: String?): String {
        val source = ExpandPass::class.java.getResourceAsStream(SHADER)?.use { String(it.readAllBytes()) }
            ?: throw IllegalStateException("$SHADER is missing")
        if (define == null) return source
        val versionEnd = source.indexOf('\n') + 1
        return source.substring(0, versionEnd) + "#define $define 1\n" + source.substring(versionEnd)
    }
}
