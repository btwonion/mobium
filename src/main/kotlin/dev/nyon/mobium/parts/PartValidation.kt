package dev.nyon.mobium.parts

import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.vertex.VertexFormat
import dev.nyon.mobium.Mobium
import dev.nyon.mobium.gpu.gl.GlCompute
import net.minecraft.client.renderer.SpriteCoordinateExpander
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import org.lwjgl.system.MemoryUtil
import java.util.Locale

/**
 * Dev check of Module 2 (self-check and gametests, never in normal play): while [PartTransform.validating] is set,
 * every part Module 2 takes over is also compiled the vanilla way (the same `compile`, into a builder of the same
 * format, through the same sprite; Iris extends it as it would the staged one), and after the expand pass the GPU's
 * vertices are read back and compared with those byte for byte. Byte 35, the normal's padding that vanilla never
 * writes, is left out. Slow (a GPU read per staged buffer); render thread only.
 */
object PartValidation {
    private const val SKIPPED_BYTE = 35
    private const val MAX_REPORTED = 8

    /** Vertices compared and how many differed in at least one byte, since [reset]. */
    var compared = 0L
        private set
    var mismatched = 0L
        private set

    /** Parts whose CPU compile wrote another number of vertices or another format than reserved. */
    var unmatchedParts = 0
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

    fun reset() {
        compared = 0
        mismatched = 0
        unmatchedParts = 0
        reports.clear()
        for (entry in entries) MemoryUtil.nmemFree(entry.expected)
        entries.clear()
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
            return
        }
        val size = expectedVertices.toLong() * last.vertexSize
        val copy = MemoryUtil.nmemAlloc(size)
        MemoryUtil.memCopy(MemoryUtil.memAddress(mesh.vertexBuffer()), copy, size)
        mesh.close()
        entries += Entry(last.staged, last.draw, last.firstVertex, last.vertexCount, last.vertexSize, copy)
    }

    /** After [staged]'s expand pass: reads its vertex buffer back and compares every validated part of it. */
    fun check(staged: StagedVertexBuffer, vertexBuffer: Int) {
        val gpuBuffer = (staged as PartStaged).`mobium$vertexBuffer`() ?: return
        val size = gpuBuffer.size()
        if (size > readbackSize) {
            if (readback != 0L) MemoryUtil.nmemFree(readback)
            readback = MemoryUtil.nmemAlloc(size)
            readbackSize = size
        }
        GlCompute.read(vertexBuffer, 0, size, readback)
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.staged !== staged) continue
            iterator.remove()
            if (entry.expected == 0L) continue
            val base = (entry.draw as PartDraw).`mobium$vertexOffset`().toLong() + entry.firstVertex.toLong() * entry.vertexSize
            for (v in 0 until entry.vertexCount) {
                val expected = entry.expected + v.toLong() * entry.vertexSize
                val actual = readback + base + v.toLong() * entry.vertexSize
                var differs = false
                for (b in 0 until entry.vertexSize) {
                    if (b == SKIPPED_BYTE) continue
                    if (MemoryUtil.memGetByte(expected + b) != MemoryUtil.memGetByte(actual + b)) {
                        differs = true
                        break
                    }
                }
                compared++
                if (differs) {
                    mismatched++
                    if (reports.size < MAX_REPORTED) reports += describe(v, entry.vertexSize, expected, actual)
                }
            }
            MemoryUtil.nmemFree(entry.expected)
        }
    }

    fun discard(staged: StagedVertexBuffer) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.staged !== staged) continue
            iterator.remove()
            if (entry.expected != 0L) MemoryUtil.nmemFree(entry.expected)
        }
    }

    fun summary(): String = "$compared vertices compared, $mismatched differ, $unmatchedParts parts unmatched"

    /** The vertex's fields that differ, CPU against GPU. */
    private fun describe(vertex: Int, vertexSize: Int, expected: Long, actual: Long): String {
        val fields = if (vertexSize == 56) IRIS_FIELDS else VANILLA_FIELDS
        val parts = ArrayList<String>()
        for ((name, offset, length) in fields) {
            var same = true
            for (b in offset until offset + length) {
                if (b != SKIPPED_BYTE && MemoryUtil.memGetByte(expected + b) != MemoryUtil.memGetByte(actual + b)) same = false
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
