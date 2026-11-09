package dev.nyon.mobium.parts

import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.QuadInstance
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.CompatMod
import dev.nyon.mobium.debug.Sections
import dev.nyon.mobium.mixins.RenderTypeFeatureRendererInvoker
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil
import net.minecraft.client.renderer.feature.ItemFeatureRenderer
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.util.ARGB
import org.lwjgl.system.MemoryUtil

/**
 * Module 2 for items (docs/architecture.md): an item submit's baked quads are not written vertex by vertex on the CPU;
 * [prepare] replaces `ItemFeatureRenderer.prepareMainSubmit` and, per run of quads with the same render type, fetches
 * the builder once, reserves the run's vertices and writes one part record and one record for the run, from the
 * list's mesh ([ItemMeshes]). The expand pass writes the vertices as the path that would have drawn them
 * (docs/vanilla-notes.md §25): vanilla's `VertexConsumer.putBakedQuad` (also while Iris extends the format, where
 * Sodium hands its quads to vanilla's; on NeoForge with the quad's baked normal), or with Sodium and the entity format
 * Sodium's `BakedModelEncoder`.
 *
 * Taken over: submits without the special glint (the plain and the standard glint write the same vertices), from
 * entity types Module 2 does not exclude, whose quad list has a mesh, run by run where the builder is a staged builder
 * in the entity format or Iris's, unsorted. A run that cannot be taken is written the vanilla way in its place; a
 * submit that cannot be taken at all returns false and vanilla writes it. Render thread only.
 */
object ItemTransform {
    /** `parts.items`; set by `PartTransform.configure`. */
    @JvmField
    var enabled = true

    private val sodium = Compat.isLoaded(CompatMod.SODIUM)
    private val tints = IntArray(ItemMeshes.MAX_TINTS)

    /**
     * Writes [submit] for [renderer] in place of `prepareMainSubmit`, with [instance], the renderer's own quad
     * instance, left as vanilla leaves it; false if vanilla should write the whole submit.
     */
    @JvmStatic
    fun prepare(renderer: ItemFeatureRenderer, submit: ItemFeatureRenderer.Submit, instance: QuadInstance): Boolean {
        if (Sections.skipModelVertices) return true
        if (!PartTransform.active || !enabled) return false
        if ((submit as PartSubmit).`mobium$partsExcluded`()) return false
        val foil = submit.foilType()
        if (foil == ItemStackRenderState.FoilType.SPECIAL) return false
        val quads = submit.quads()
        if (quads.isEmpty()) return false
        try {
            val mesh = ItemMeshes.of(quads) ?: return false
            prepareRuns(renderer as RenderTypeFeatureRendererInvoker, submit, instance, quads, foil, mesh)
            return true
        } catch (e: Throwable) {
            PartTransform.failed(e)
            return false
        }
    }

    private fun prepareRuns(
        renderer: RenderTypeFeatureRendererInvoker,
        submit: ItemFeatureRenderer.Submit,
        instance: QuadInstance,
        quads: List<BakedQuad>,
        foil: ItemStackRenderState.FoilType,
        mesh: ItemMeshes.Mesh
    ) {
        instance.setLightCoords(submit.lightCoords())
        instance.setOverlayCoords(submit.overlayCoords())
        val layers = submit.tintLayers()
        for (t in 0 until tints.size) tints[t] = if (t < layers.size) layers[t] else -1
        val count = quads.size
        var taken = false
        var start = 0
        while (start < count) {
            val renderType = renderType(quads[start], foil)
            var end = start + 1
            while (end < count && renderType(quads[end], foil) === renderType) end++
            // Vanilla asks for the builder per quad; for one render type it hands out the same builder each time.
            val consumer = renderer.`mobium$getVertexBuilder`(renderType)
            if (takeRun(consumer, submit, instance, quads, mesh, start, end)) {
                taken = true
            } else {
                for (i in start until end) {
                    val quad = quads[i]
                    instance.setColor(layerColor(layers, quad))
                    val builder = if (i == start) consumer else renderer.`mobium$getVertexBuilder`(renderType)
                    builder.putBakedQuad(submit.pose(), quad, instance)
                }
            }
            start = end
        }
        // Sodium's putBakedQuad marks each quad's sprite as drawn (Sodium animates only those); vanilla does not track.
        if (taken && sodium) {
            val sprites = mesh.sprites
            for (i in 0 until sprites.size) SpriteUtil.INSTANCE.markSpriteActive(sprites[i])
        }
    }

    /**
     * Reserves the vertices of [quads] from [start] to [end] in [consumer] and writes their records, if [consumer] is a
     * staged builder Module 2 can write for; false leaves the run to the caller.
     */
    private fun takeRun(
        consumer: VertexConsumer,
        submit: ItemFeatureRenderer.Submit,
        instance: QuadInstance,
        quads: List<BakedQuad>,
        mesh: ItemMeshes.Mesh,
        start: Int,
        end: Int
    ): Boolean {
        if (consumer !is BufferBuilder) return false
        val target = consumer as PartTarget
        val staged = target.`mobium$staged`() ?: return false
        val draw = target.`mobium$draw`() ?: return false
        if ((draw as PartDraw).`mobium$sorted`()) return false
        if (!target.`mobium$building`() || target.`mobium$topology`() != PrimitiveTopology.QUADS) return false
        val format = target.`mobium$format`()
        val iris: Boolean
        val sodiumPath: Boolean
        when {
            // Sodium's BufferBuilder.putBakedQuad writes the entity format through its BakedModelEncoder.
            format === DefaultVertexFormat.ENTITY -> {
                iris = false
                sodiumPath = sodium
            }
            // Iris's format is not the entity format: Sodium hands those quads to vanilla's putBakedQuad.
            format === PartTransform.irisFormat() -> {
                iris = true
                sodiumPath = false
            }
            else -> return false
        }
        val pose = submit.pose()
        // Sodium and NeoForge transform each quad's own normal, which needs the normal matrix on the GPU.
        if (sodiumPath && !ItemMeshes.sodiumNormals) return false
        if ((sodiumPath || mesh.bakedNormals) && !PartTransform.gpuNormals(pose.normal())) return false
        val runQuads = end - start
        if (!PartTransform.fits(target, runQuads * 4)) return false

        // Everything checked: from here on the run is Module 2's.
        val offset = PartTransform.reserve(consumer, runQuads, iris)
        val vertexSize = format.vertexSize
        val drawBytes = target.`mobium$drawBytesBefore`() + offset
        val firstVertex = drawBytes / vertexSize
        val batch = PartTransform.batchOf(staged)
        val partIndex = batch.partCount
        val slot = batch.slotOf(draw)
        batch.reserved(slot).add(drawBytes, drawBytes + runQuads * 4 * vertexSize)
        val address = batch.addPart()
        val extra = PartBatch.FLAG_ITEM or if (sodiumPath) PartBatch.FLAG_ITEM_SODIUM else 0
        val light = submit.lightCoords()
        val flags = PartTransform.writePart(address, pose, light, submit.overlayCoords(), -1, null, iris, slot, extra)
        for (t in 0 until tints.size) MemoryUtil.memPutInt(address + (PartBatch.P_TINTS + t) * 4L, ARGB.toABGR(tints[t]))
        batch.addCube(mesh.firstQuad + start, runQuads, partIndex, firstVertex)
        PartTransform.items++
        PartTransform.itemQuads += runQuads

        if (PartTransform.validating || PartCheck.sample(PartCheck.path(flags, false, ExpandPass.lastStage))) {
            // Compared with the CPU's vertices: the run is written once more, the vanilla way, into the check's builder.
            PartValidation.begin(staged, draw, firstVertex, runQuads * 4, format, null)
            try {
                val check = PartValidation.consumer()
                for (i in start until end) {
                    instance.setColor(layerColor(submit.tintLayers(), quads[i]))
                    check.putBakedQuad(pose, quads[i], instance)
                }
            } catch (e: Throwable) {
                PartTransform.validationFailed(e)
            } finally {
                PartTransform.endValidation()
            }
        }
        // As vanilla leaves the quad instance after the run's last quad.
        instance.setColor(layerColor(submit.tintLayers(), quads[end - 1]))
        return true
    }

    private fun renderType(quad: BakedQuad, foil: ItemStackRenderState.FoilType): RenderType {
        val material = quad.materialInfo()
        return if (foil == ItemStackRenderState.FoilType.STANDARD) material.itemGlintRenderType() else material.itemRenderType()
    }

    /** `ItemFeatureRenderer.getLayerColorSafe`: the quad's tint layer colour, white when untinted or out of range. */
    private fun layerColor(layers: IntArray, quad: BakedQuad): Int {
        val material = quad.materialInfo()
        if (!material.isTinted) return -1
        val layer = material.tintIndex()
        return if (layer >= 0 && layer < layers.size) layers[layer] else -1
    }
}
