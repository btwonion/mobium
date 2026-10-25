package dev.nyon.mobium.parts

import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.vertex.VertexFormat
import com.mojang.renderpearl.backend.opengl.GlBuffer
import dev.nyon.mobium.Mobium
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.CompatMod
import dev.nyon.mobium.core.Fallback
import dev.nyon.mobium.core.IrisCompat
import dev.nyon.mobium.core.Module
import dev.nyon.mobium.debug.Sections
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.SpriteCoordinateExpander
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil

/**
 * Module 2, the GPU part transform (PLAN.md M6): a model part's cubes are not written vertex by vertex on the CPU;
 * [compile] reserves their vertices in the staged buffer instead and writes one part record and one record per cube,
 * and after the staged buffer's upload [ExpandPass] writes the vertices on the GPU, exactly as the CPU path would have
 * (`expand_parts.comp`, docs/vanilla-notes.md §16). Posing, the part walk and EMF's and ETF's hooks run as before;
 * vanilla's draws draw the vertices.
 *
 * Taken over: parts whose cubes are all EMF's `EMFCube` (Fresh Animations), or vanilla cubes without Sodium (with
 * Sodium they take Sodium's own path, not reproduced yet), written to a staged draw in the entity format or Iris's
 * extended one, unsorted quads, directly or through an atlas sprite. Everything else returns false and compiles as
 * before. Render thread only.
 */
object PartTransform {
    /** Set from the module state ([setActive]). */
    @JvmField
    var active = false

    /** Dev validation ([PartValidation]): every part taken over is also compiled on the CPU and compared. */
    @JvmField
    var validating = false

    /** Parts and quads taken over in the last frame, for F3. */
    @JvmField
    var lastParts = 0

    @JvmField
    var lastQuads = 0

    private var parts = 0
    private var quads = 0

    private val sodium = Compat.isLoaded(CompatMod.SODIUM)
    private val emfCube: Class<*>? = if (Compat.isLoaded(CompatMod.EMF)) {
        runCatching { Class.forName("traben.entity_model_features.models.parts.EMFModelPartCustom\$EMFCube") }.getOrNull()
    } else {
        null
    }
    private var irisFormat: VertexFormat? = null
    private var irisFormatLooked = false

    private val normal = Vector3f()
    private val normals = IntArray(6)

    /** A valid address for the empty push's source; nothing is read from it. */
    private val emptyPush = MemoryUtil.nmemAlloc(64)

    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        if (!active) MeshPool.clear()
    }

    /**
     * Takes over one part's cubes if it can; false means the caller compiles them as vanilla would. Called by the
     * wrappers of `ModelPart.compile` and EMF's `EMFModelPart.compile`.
     */
    @JvmStatic
    fun compile(
        cubes: List<ModelPart.Cube>,
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        light: Int,
        overlay: Int,
        color: Int
    ): Boolean {
        if (Sections.skipModelVertices) return true
        if (!active) return false
        try {
            return tryCompile(cubes, pose, consumer, light, overlay, color)
        } catch (e: Throwable) {
            Fallback.moduleFailed(Module.PART_TRANSFORM, e)
            return false
        }
    }

    private fun tryCompile(
        cubes: List<ModelPart.Cube>,
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        light: Int,
        overlay: Int,
        color: Int
    ): Boolean {
        val builder: BufferBuilder
        var sprite: TextureAtlasSprite? = null
        if (consumer is BufferBuilder) {
            builder = consumer
        } else if (consumer is SpriteCoordinateExpander) {
            val delegate = consumer.delegate()
            val mapping = consumer.mapping()
            if (delegate !is BufferBuilder || mapping.javaClass !== TextureAtlasSprite::class.java) return false
            builder = delegate
            sprite = mapping as TextureAtlasSprite
        } else {
            return false
        }
        val target = builder as PartTarget
        val staged = target.`mobium$staged`() ?: return false
        val draw = target.`mobium$draw`() ?: return false
        if ((draw as PartDraw).`mobium$sorted`()) return false
        if (!target.`mobium$building`() || target.`mobium$topology`() != PrimitiveTopology.QUADS) return false
        val format = target.`mobium$format`()
        val iris = when {
            format === DefaultVertexFormat.ENTITY -> false
            format === irisFormat() -> true
            else -> return false
        }

        var quadTotal = 0
        for (i in 0 until cubes.size) {
            val cube = cubes[i]
            val type = cube.javaClass
            if (type !== emfCube && (type !== ModelPart.Cube::class.java || sodium)) return false
            if (MeshPool.firstQuad(cube) < 0) return false
            quadTotal += cube.polygons.size
        }
        if (quadTotal == 0) return false
        if (target.`mobium$vertexCount`() + quadTotal * 4 > MAX_VERTICES) return false

        // Everything checked: from here on the part is Module 2's.
        val offset = target.`mobium$reserve`(quadTotal * 4)
        // Iris counts each vertex at the next endLastVertex to extend it, except after a Sodium push; an empty push
        // marks the reserved vertices as pushed (Iris's push TAIL hook), as Sodium's cube path would have.
        if (iris) (builder as VertexBufferWriter).push(MemoryStack.stackGet(), emptyPush, 0, format)
        val vertexSize = format.vertexSize
        var firstVertex = (target.`mobium$drawBytesBefore`() + offset) / vertexSize

        val batch = batchOf(staged)
        val partIndex = batch.partCount
        writePart(batch.addPart(), pose, light, overlay, color, sprite, iris, batch.slotOf(draw))
        for (i in 0 until cubes.size) {
            val cube = cubes[i]
            val cubeQuads = cube.polygons.size
            batch.addCube(MeshPool.firstQuad(cube), cubeQuads, partIndex, firstVertex)
            firstVertex += cubeQuads * 4
        }
        parts++
        quads += quadTotal
        if (validating) PartValidation.begin(staged, draw, (target.`mobium$drawBytesBefore`() + offset) / vertexSize, quadTotal * 4, format, sprite)
        return true
    }

    private fun writePart(
        address: Long,
        pose: PoseStack.Pose,
        light: Int,
        overlay: Int,
        color: Int,
        sprite: TextureAtlasSprite?,
        iris: Boolean,
        slot: Int
    ) {
        val matrix: Matrix4f = pose.pose()
        MemoryUtil.memPutFloat(address, matrix.m00())
        MemoryUtil.memPutFloat(address + 4, matrix.m01())
        MemoryUtil.memPutFloat(address + 8, matrix.m02())
        MemoryUtil.memPutFloat(address + 12, matrix.m10())
        MemoryUtil.memPutFloat(address + 16, matrix.m11())
        MemoryUtil.memPutFloat(address + 20, matrix.m12())
        MemoryUtil.memPutFloat(address + 24, matrix.m20())
        MemoryUtil.memPutFloat(address + 28, matrix.m21())
        MemoryUtil.memPutFloat(address + 32, matrix.m22())
        MemoryUtil.memPutFloat(address + 36, matrix.m30())
        MemoryUtil.memPutFloat(address + 40, matrix.m31())
        MemoryUtil.memPutFloat(address + 44, matrix.m32())

        // Cube.compile: pose.transformNormal(polygon normal) per polygon, packed by BufferBuilder. The polygon normals
        // are axis unit vectors, and the transform of -e is -(transform of e) bit for bit up to the sign of zeros
        // (the length and its square root are the same, truncation is symmetric), so three transforms cover all six.
        packNormal(pose, 1f, 0f, 0f, DIRECTION_EAST, DIRECTION_WEST)
        packNormal(pose, 0f, 1f, 0f, DIRECTION_UP, DIRECTION_DOWN)
        packNormal(pose, 0f, 0f, 1f, DIRECTION_SOUTH, DIRECTION_NORTH)
        for (d in 0 until 6) MemoryUtil.memPutInt(address + (PartBatch.P_NORMALS + d) * 4L, normals[d])

        MemoryUtil.memPutInt(address + PartBatch.P_COLOR * 4L, ARGB.toABGR(color))
        MemoryUtil.memPutInt(address + PartBatch.P_OVERLAY * 4L, overlay)
        MemoryUtil.memPutInt(address + PartBatch.P_LIGHT * 4L, light)

        val properties = matrix.properties()
        var flags = when {
            properties and PROPERTY_IDENTITY != 0 -> PartBatch.KIND_IDENTITY
            properties and PROPERTY_TRANSLATION != 0 -> PartBatch.KIND_TRANSLATION
            else -> PartBatch.KIND_GENERIC
        }
        if (sprite != null) {
            flags = flags or PartBatch.FLAG_SPRITE
            MemoryUtil.memPutFloat(address + PartBatch.P_SPRITE * 4L, sprite.u0)
            MemoryUtil.memPutFloat(address + (PartBatch.P_SPRITE + 1) * 4L, sprite.u1 - sprite.u0)
            MemoryUtil.memPutFloat(address + (PartBatch.P_SPRITE + 2) * 4L, sprite.v0)
            MemoryUtil.memPutFloat(address + (PartBatch.P_SPRITE + 3) * 4L, sprite.v1 - sprite.v0)
        }
        var ids = 0
        var item = 0
        if (iris) {
            flags = flags or PartBatch.FLAG_IRIS
            if (IrisCompat.isRenderingLevel()) flags = flags or PartBatch.FLAG_IRIS_FACE_NORMAL
            ids = (IrisCompat.currentEntity() and 0xFFFF) or (IrisCompat.currentBlockEntity() shl 16)
            item = IrisCompat.currentItem() and 0xFFFF
        }
        MemoryUtil.memPutInt(address + PartBatch.P_FLAGS * 4L, flags)
        MemoryUtil.memPutInt(address + PartBatch.P_SLOT * 4L, slot)
        MemoryUtil.memPutInt(address + PartBatch.P_IDS * 4L, ids)
        MemoryUtil.memPutInt(address + (PartBatch.P_IDS + 1) * 4L, item)
    }

    private fun packNormal(pose: PoseStack.Pose, x: Float, y: Float, z: Float, positive: Int, negative: Int) {
        pose.transformNormal(x, y, z, normal)
        normals[positive] = packNormal(normal.x, normal.y, normal.z)
        normals[negative] = packNormal(-normal.x, -normal.y, -normal.z)
    }

    // BufferBuilder.normalIntValue per component; the fourth byte stays 0.
    private fun packNormal(x: Float, y: Float, z: Float): Int =
        normalByte(x) or (normalByte(y) shl 8) or (normalByte(z) shl 16)

    private fun normalByte(c: Float): Int = (Mth.clamp(c, -1.0f, 1.0f) * 127.0f).toInt() and 0xFF

    private fun batchOf(staged: StagedVertexBuffer): PartBatch {
        val holder = staged as PartStaged
        holder.`mobium$batch`()?.let { return it }
        val batch = PartBatch()
        holder.`mobium$setBatch`(batch)
        return batch
    }

    private fun irisFormat(): VertexFormat? {
        if (!irisFormatLooked) {
            irisFormatLooked = true
            irisFormat = if (Compat.isLoaded(CompatMod.IRIS)) IrisCompat.entityFormat() else null
        }
        return irisFormat
    }

    /** Runs the expand pass for what [staged] collected; called right after its `upload()`. */
    @JvmStatic
    fun afterUpload(staged: StagedVertexBuffer) {
        val batch = (staged as PartStaged).`mobium$batch`() ?: return
        if (batch.isEmpty) return
        try {
            val vertexBuffer = staged.`mobium$vertexBuffer`() as GlBuffer
            batch.resolveSlots()
            ExpandPass.dispatch(batch, vertexBuffer.handle())
            if (validating) PartValidation.check(staged, vertexBuffer.handle())
        } catch (e: Throwable) {
            Fallback.moduleFailed(Module.PART_TRANSFORM, e)
        } finally {
            batch.clear()
        }
    }

    /** Drops what [staged] collected without an upload (its frame ended early). */
    @JvmStatic
    fun discard(staged: StagedVertexBuffer) {
        val batch = (staged as PartStaged).`mobium$batch`() ?: return
        if (!batch.isEmpty) {
            Mobium.LOGGER.debug("Mobium part_transform: records discarded without an upload")
            batch.clear()
        }
        if (validating) PartValidation.discard(staged)
    }

    /** Closes the frame's counters; called once per frame. */
    @JvmStatic
    fun endFrame() {
        lastParts = parts
        lastQuads = quads
        parts = 0
        quads = 0
    }

    /** F3 `mobium:modules`: what Module 2 took over in the last frame and what its meshes hold. */
    fun debugLine(): String = String.format(
        java.util.Locale.ROOT,
        "Mobium part transform: %d parts, %d quads last frame; meshes %d quads; GPU %.1f MB",
        lastParts, lastQuads, MeshPool.quadCount, ExpandPass.bytesAllocated / 1048576.0
    )

    /** A new level, a resource reload or a render distance change: every mesh is written again. */
    @JvmStatic
    fun clearMeshes() {
        MeshPool.clear()
    }

    /** The consumer the validation's CPU compile writes into (a builder in the same format, same sprite). */
    @JvmStatic
    fun validationConsumer(): VertexConsumer = PartValidation.consumer()

    @JvmStatic
    fun endValidation() = PartValidation.end()

    private const val MAX_VERTICES = 16777215
    private const val PROPERTY_IDENTITY = 1 shl 2
    private const val PROPERTY_TRANSLATION = 1 shl 3

    // Direction ordinals.
    private const val DIRECTION_DOWN = 0
    private const val DIRECTION_UP = 1
    private const val DIRECTION_NORTH = 2
    private const val DIRECTION_SOUTH = 3
    private const val DIRECTION_WEST = 4
    private const val DIRECTION_EAST = 5
}
