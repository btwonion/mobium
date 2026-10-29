package dev.nyon.mobium.parts

import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.vertex.VertexFormat
import com.mojang.renderpearl.backend.opengl.GlBuffer
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer
import dev.nyon.mobium.Mobium
import dev.nyon.mobium.gpu.gl.GlCompute
import net.minecraft.client.renderer.SpriteCoordinateExpander
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import org.lwjgl.system.MemoryUtil
import java.util.Locale

/**
 * Compares Module 2's GPU vertices with the CPU's, for the parts [PartTransform] picks: every part while the dev
 * validation runs ([PartTransform.validating]: self-check and gametests), a few per path in normal play ([PartCheck]).
 * Such a part is also compiled the vanilla way (the same `compile`, into a builder of the same format, through the
 * same sprite; Iris extends it as it would the staged one), and after the expand pass the GPU's vertices are read back
 * and compared with those byte for byte. Left out are the bytes the CPU path never writes, so that they hold whatever
 * the memory held before: byte 35, the normal's padding, and in Iris's format bytes 42 and 43, the fourth short of
 * `iris_Entity`, which Iris's Sodium serializer leaves alone (its per-vertex path writes 0, as the expand pass does).
 * Slow (a GPU read per staged buffer with compared parts); render thread only.
 *
 * On OpenGL the read waits for the GPU. Vulkan cannot wait inside a frame for commands it has not submitted, so there
 * the span is copied into a read-back buffer and compared once the frame's fence has signalled ([poll], a few frames
 * later); in normal play the CPU's bytes are written over every compared part in the same frame, so that no compared
 * part is drawn from the GPU, and the verdict switches Module 2 off as on OpenGL.
 */
object PartValidation {
    private const val NORMAL_PADDING = 35
    private const val IRIS_ID_PADDING = 42
    private const val MAX_REPORTED = 8

    /** Vertices compared and how many differed in at least one byte, since [reset]. */
    var compared = 0L
        private set
    var mismatched = 0L
        private set

    /** Parts whose CPU compile wrote another number of vertices or another format than reserved. */
    var unmatchedParts = 0
        private set

    /** Differing vertices [check] wrote the CPU's bytes over, and those still differing when read again (stays 0). */
    var repaired = 0L
        private set
    var unrepaired = 0L
        private set

    /** The first differences, one line each. */
    val reports = ArrayList<String>()

    private val bytes = ByteBufferBuilder(1 shl 20)
    private var builder: BufferBuilder? = null
    private var consumer: VertexConsumer? = null
    private var expectedFormat: VertexFormat? = null
    private var expectedVertices = 0

    private class Entry(
        val staged: StagedVertexBuffer,
        val draw: StagedVertexBuffer.Draw,
        val firstVertex: Int,
        val vertexCount: Int,
        val vertexSize: Int,
        val expected: Long
    )

    private val entries = ArrayList<Entry>()
    private var readback = 0L
    private var readbackSize = 0L

    /** A Vulkan read-back waiting for its fence: the parts it holds, from [start] of their vertex buffer. */
    private class Pending(
        val readback: VulkanExpand.Readback,
        val start: Long,
        val entries: ArrayList<Entry>,
        val unmatched: Int,
        val repair: Boolean,
        val forced: Boolean,
        val stage: String
    )

    private val pendingChecks = ArrayList<Pending>()

    /** No Vulkan read-back is waiting for the GPU: the counters are final. */
    val settled: Boolean
        get() = pendingChecks.isEmpty()

    fun reset() {
        compared = 0
        mismatched = 0
        unmatchedParts = 0
        repaired = 0
        unrepaired = 0
        reports.clear()
        for (entry in entries) MemoryUtil.nmemFree(entry.expected)
        entries.clear()
        for (pending in pendingChecks) {
            for (entry in pending.entries) MemoryUtil.nmemFree(entry.expected)
            VulkanExpand.release(pending.readback)
        }
        pendingChecks.clear()
    }

    /** A part Module 2 just took over: [vertexCount] vertices from [firstVertex] of [draw]. */
    fun begin(
        staged: StagedVertexBuffer,
        draw: StagedVertexBuffer.Draw,
        firstVertex: Int,
        vertexCount: Int,
        format: VertexFormat,
        sprite: TextureAtlasSprite?
    ) {
        val created = BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY)
        builder = created
        consumer = if (sprite != null) SpriteCoordinateExpander(created, sprite) else created
        expectedFormat = format
        expectedVertices = vertexCount
        entries += Entry(staged, draw, firstVertex, vertexCount, format.vertexSize, 0L)
    }

    fun consumer(): VertexConsumer = consumer ?: throw IllegalStateException("no part in validation")

    /** The CPU compile has written the part: keep its vertices. */
    fun end() {
        val created = builder ?: return
        builder = null
        consumer = null
        val mesh = created.build()
        val last = entries.removeAt(entries.size - 1)
        val format = (created as PartTarget).`mobium$format`()
        if (mesh == null || format !== expectedFormat || mesh.drawState().vertexCount() != expectedVertices) {
            unmatchedParts++
            if (reports.size < MAX_REPORTED) {
                reports += "part with ${mesh?.drawState()?.vertexCount() ?: 0} CPU vertices in $format, reserved $expectedVertices in $expectedFormat"
            }
            mesh?.close()
            // Kept without bytes, so that [check] counts it for its staged buffer.
            entries += last
            return
        }
        val size = expectedVertices.toLong() * last.vertexSize
        val copy = MemoryUtil.nmemAlloc(size)
        MemoryUtil.memCopy(MemoryUtil.memAddress(mesh.vertexBuffer()), copy, size)
        mesh.close()
        entries += Entry(last.staged, last.draw, last.firstVertex, last.vertexCount, last.vertexSize, copy)
    }

    /** Differing vertices and parts the CPU wrote otherwise, found by the last [check]. */
    var lastDiffering = 0L
        private set
    var lastUnmatched = 0
        private set

    /** Whether parts of [staged] wait for [check]. */
    fun pending(staged: StagedVertexBuffer): Boolean {
        for (i in 0 until entries.size) if (entries[i].staged === staged) return true
        return false
    }

    /**
     * After [staged]'s expand pass in [stage]: compares its compared parts with the GPU's vertices in [vertexBuffer];
     * with [repair] (normal play, [PartCheck]) a difference switches Module 2 off and the frame draws the CPU's bytes.
     */
    fun check(staged: StagedVertexBuffer, vertexBuffer: GpuBuffer, repair: Boolean, stage: String) {
        if (vertexBuffer is GlBuffer) {
            checkGl(staged, vertexBuffer.handle(), repair)
            finish(lastDiffering, lastUnmatched, repair, stage)
        } else {
            checkVulkan(staged, vertexBuffer as VulkanGpuBuffer, repair, stage)
        }
    }

    /** A comparison's verdict: in normal play a difference makes Module 2 fail ([PartCheck.failed]). */
    private fun finish(differing: Long, unmatched: Int, repair: Boolean, stage: String) {
        if (repair && (differing > 0 || unmatched > 0)) PartCheck.failed(differing, unmatched, stage)
    }

    /**
     * OpenGL: reads back the span of [staged]'s vertex buffer that holds compared parts and compares each part; with
     * [repair], writes the CPU's bytes over a part whose vertices differ and reads it again, so that this frame draws
     * the CPU's vertices. Sets [lastDiffering] and [lastUnmatched].
     */
    private fun checkGl(staged: StagedVertexBuffer, vertexBuffer: Int, repair: Boolean) {
        lastDiffering = 0
        lastUnmatched = 0
        var start = Long.MAX_VALUE
        var end = 0L
        for (i in 0 until entries.size) {
            val entry = entries[i]
            if (entry.staged !== staged || entry.expected == 0L) continue
            start = minOf(start, base(entry))
            end = maxOf(end, base(entry) + entry.vertexCount.toLong() * entry.vertexSize)
        }
        if (end > start) {
            if (PartCheck.forcedDifference) forceDifference(vertexBuffer, start)
            if (end - start > readbackSize) {
                if (readback != 0L) MemoryUtil.nmemFree(readback)
                readback = MemoryUtil.nmemAlloc(end - start)
                readbackSize = end - start
            }
            GlCompute.read(vertexBuffer, start, end - start, readback)
        }
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.staged !== staged) continue
            iterator.remove()
            if (entry.expected == 0L) {
                lastUnmatched++
                continue
            }
            val base = base(entry)
            val actual = readback + (base - start)
            val differing = differing(entry, actual, report = true)
            compared += entry.vertexCount
            mismatched += differing
            lastDiffering += differing
            if (differing > 0 && repair) {
                val bytes = entry.vertexCount.toLong() * entry.vertexSize
                GlCompute.upload(vertexBuffer, base, bytes, entry.expected)
                GlCompute.read(vertexBuffer, base, bytes, actual)
                repaired += differing
                unrepaired += differing(entry, actual, report = false)
            }
            MemoryUtil.nmemFree(entry.expected)
        }
    }

    /**
     * Vulkan: records a copy of the span holding [staged]'s compared parts and, with [repair], the CPU's bytes written
     * over all of them; the comparison follows in [poll].
     */
    private fun checkVulkan(staged: StagedVertexBuffer, vertexBuffer: VulkanGpuBuffer, repair: Boolean, stage: String) {
        poll()
        val taken = ArrayList<Entry>()
        var unmatched = 0
        var start = Long.MAX_VALUE
        var end = 0L
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.staged !== staged) continue
            iterator.remove()
            if (entry.expected == 0L) {
                unmatched++
                continue
            }
            taken += entry
            start = minOf(start, base(entry))
            end = maxOf(end, base(entry) + entry.vertexCount.toLong() * entry.vertexSize)
        }
        if (taken.isEmpty()) {
            finish(0, unmatched, repair, stage)
            return
        }
        val repairs = if (repair) taken.size else 0
        val offsets = LongArray(repairs)
        val addresses = LongArray(repairs)
        val sizes = LongArray(repairs)
        for (i in 0 until repairs) {
            offsets[i] = base(taken[i])
            addresses[i] = taken[i].expected
            sizes[i] = taken[i].vertexCount.toLong() * taken[i].vertexSize
        }
        val readback = VulkanExpand.readBack(vertexBuffer, start, end - start, offsets, addresses, sizes, repairs)
        val forced = PartCheck.forcedDifference
        PartCheck.forcedDifference = false
        pendingChecks += Pending(readback, start, taken, unmatched, repair, forced, stage)
    }

    /** Compares the Vulkan read-backs the GPU has finished; called every frame and before each check. */
    fun poll() {
        if (pendingChecks.isEmpty()) return
        val iterator = pendingChecks.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            if (!pending.readback.fence.awaitCompletion(0)) continue
            iterator.remove()
            compare(pending)
        }
    }

    private fun compare(pending: Pending) {
        pending.readback.read()
        val before = pending.readback.address(afterRepairs = false)
        // `/mobium debug gpucheck`: one changed bit in the x position of the first compared vertex, as a driver might.
        if (pending.forced) MemoryUtil.memPutInt(before, MemoryUtil.memGetInt(before) xor 1)
        var differing = 0L
        for (entry in pending.entries) {
            val offset = base(entry) - pending.start
            val found = differing(entry, before + offset, report = true)
            compared += entry.vertexCount
            mismatched += found
            differing += found
            if (found > 0 && pending.repair) {
                repaired += found
                unrepaired += differing(entry, pending.readback.address(afterRepairs = true) + offset, report = false)
            }
            MemoryUtil.nmemFree(entry.expected)
        }
        VulkanExpand.release(pending.readback)
        finish(differing, pending.unmatched, pending.repair, pending.stage)
    }

    /** Where [entry]'s first vertex lies in its staged buffer's vertex buffer, in bytes. */
    private fun base(entry: Entry): Long =
        (entry.draw as PartDraw).`mobium$vertexOffset`().toLong() + entry.firstVertex.toLong() * entry.vertexSize

    /** The vertices of [entry] that differ from those at [actual] (CPU against GPU); [report] describes the first. */
    private fun differing(entry: Entry, actual: Long, report: Boolean): Long {
        var count = 0L
        for (v in 0 until entry.vertexCount) {
            val expected = entry.expected + v.toLong() * entry.vertexSize
            val vertex = actual + v.toLong() * entry.vertexSize
            for (b in 0 until entry.vertexSize) {
                if (skipped(b, entry.vertexSize)) continue
                if (MemoryUtil.memGetByte(expected + b) != MemoryUtil.memGetByte(vertex + b)) {
                    count++
                    if (report && reports.size < MAX_REPORTED) reports += describe(v, entry.vertexSize, expected, vertex)
                    break
                }
            }
        }
        return count
    }

    /** `/mobium debug gpucheck`: flips the lowest bit of the x position at [offset] on the GPU, as a driver might. */
    private fun forceDifference(vertexBuffer: Int, offset: Long) {
        PartCheck.forcedDifference = false
        val scratch = MemoryUtil.nmemAlloc(4)
        GlCompute.read(vertexBuffer, offset, 4, scratch)
        MemoryUtil.memPutInt(scratch, MemoryUtil.memGetInt(scratch) xor 1)
        GlCompute.upload(vertexBuffer, offset, 4, scratch)
        MemoryUtil.nmemFree(scratch)
    }

    /** Drops the parts of [staged] that were not compared; per staged buffer per frame, allocates nothing if none. */
    fun discard(staged: StagedVertexBuffer) {
        if (entries.isEmpty()) return
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.staged !== staged) continue
            iterator.remove()
            if (entry.expected != 0L) MemoryUtil.nmemFree(entry.expected)
        }
    }

    private fun skipped(byte: Int, vertexSize: Int): Boolean =
        byte == NORMAL_PADDING || (vertexSize == 56 && (byte == IRIS_ID_PADDING || byte == IRIS_ID_PADDING + 1))

    fun summary(): String = "$compared vertices compared, $mismatched differ, $unmatchedParts parts unmatched"

    /** The vertex's fields that differ, CPU against GPU. */
    private fun describe(vertex: Int, vertexSize: Int, expected: Long, actual: Long): String {
        val fields = if (vertexSize == 56) IRIS_FIELDS else VANILLA_FIELDS
        val parts = ArrayList<String>()
        for ((name, offset, length) in fields) {
            var same = true
            for (b in offset until offset + length) {
                if (!skipped(b, vertexSize) && MemoryUtil.memGetByte(expected + b) != MemoryUtil.memGetByte(actual + b)) same = false
            }
            if (same) continue
            parts += when (name) {
                "position", "uv", "mid uv" -> {
                    val cpu = (0 until length / 4).joinToString(" ") { floatText(MemoryUtil.memGetFloat(expected + offset + it * 4)) }
                    val gpu = (0 until length / 4).joinToString(" ") { floatText(MemoryUtil.memGetFloat(actual + offset + it * 4)) }
                    "$name cpu [$cpu] gpu [$gpu]"
                }
                else -> {
                    val cpu = (0 until length).joinToString("") { String.format(Locale.ROOT, "%02x", MemoryUtil.memGetByte(expected + offset + it)) }
                    val gpu = (0 until length).joinToString("") { String.format(Locale.ROOT, "%02x", MemoryUtil.memGetByte(actual + offset + it)) }
                    "$name cpu $cpu gpu $gpu"
                }
            }
        }
        val line = "vertex $vertex of a part (${vertexSize} bytes): ${parts.joinToString("; ")}"
        Mobium.LOGGER.warn("Mobium part_transform validation: {}", line)
        return line
    }

    private fun floatText(value: Float): String =
        String.format(Locale.ROOT, "%s (%08x)", value, java.lang.Float.floatToRawIntBits(value))

    private val VANILLA_FIELDS = listOf(
        Triple("position", 0, 12), Triple("color", 12, 4), Triple("uv", 16, 8), Triple("overlay", 24, 4),
        Triple("light", 28, 4), Triple("normal", 32, 4)
    )
    private val IRIS_FIELDS = VANILLA_FIELDS + listOf(
        Triple("ids", 36, 8), Triple("mid uv", 44, 8), Triple("tangent", 52, 4)
    )
}
