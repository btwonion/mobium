package dev.nyon.mobium.parts

import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.CompatMod
import net.minecraft.client.model.geom.builders.UVPair
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.resources.model.geometry.BakedQuad
import org.lwjgl.system.MemoryUtil
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.IdentityHashMap

/**
 * Module 2's item meshes ([MeshPool], item layout): one mesh per list of baked quads an item submit draws
 * (`ItemQuads.solid()` / `translucent()`, built once per model and kept until a resource reload), written the first time
 * the list is drawn. A quad holds its four positions and UVs as `BakedQuad` does, its `Direction`, tint index and light
 * emission, and the quad's packed normal: with Sodium the one Sodium's `BakedModelEncoder` transforms
 * (`getAccurateNormal`, read from Sodium itself so that each platform's own value is used), else NeoForge's baked
 * normal. A list with a quad the expand pass cannot reproduce (more than four tint layers, an emission outside 0 to
 * 15, per-vertex normals, colours from NeoForge or Sodium) is remembered as such and stays on the vanilla path. Lookups per submit allocate nothing; render thread only.
 */
object ItemMeshes {
    /** A list's mesh: its first quad in [MeshPool], or -1 when it stays vanilla, and its distinct sprites. */
    class Mesh(
        @JvmField val generation: Int,
        @JvmField val firstQuad: Int,
        @JvmField val sprites: Array<TextureAtlasSprite>,
        /** A quad has NeoForge's baked normal, which goes through the normal matrix on the GPU. */
        @JvmField val bakedNormals: Boolean
    )

    private val meshes = IdentityHashMap<List<BakedQuad>, Mesh>()
    private var lastQuads: List<BakedQuad>? = null
    private var lastMesh: Mesh? = null
    private val noSprites = arrayOf<TextureAtlasSprite>()

    /** The tint layers the expand pass has room for (`PartBatch.P_TINTS`). */
    const val MAX_TINTS = 4

    private val sodium = Compat.isLoaded(CompatMod.SODIUM)

    /** Sodium's `ModelQuadView.getAccurateNormal(int)` and `getColor(int)`, which its item path writes from. */
    private val accurateNormal: MethodHandle? = sodiumMethod("getAccurateNormal")
    private val sodiumColor: MethodHandle? = sodiumMethod("getColor")

    /** The meshes hold Sodium's normals: Sodium's item path can be expanded (false if Sodium changed these methods). */
    @JvmField
    val sodiumNormals = accurateNormal != null && sodiumColor != null

    /** Quads in the item meshes written since the last [clear], for F3. */
    var quadCount = 0
        private set

    /** The mesh of [quads], writing it on first sight; null if the list stays on the vanilla path. */
    fun of(quads: List<BakedQuad>): Mesh? {
        val last = lastMesh
        val mesh = if (quads === lastQuads && last != null && last.generation == MeshPool.generation) {
            last
        } else {
            var found = meshes[quads]
            if (found == null || found.generation != MeshPool.generation) {
                found = write(quads)
                meshes[quads] = found
            }
            lastQuads = quads
            lastMesh = found
            found
        }
        return if (mesh.firstQuad >= 0) mesh else null
    }

    /** Every mesh is written again when next drawn; called by [MeshPool.clear]. */
    fun clear() {
        meshes.clear()
        lastQuads = null
        lastMesh = null
        quadCount = 0
    }

    private fun write(quads: List<BakedQuad>): Mesh {
        val count = quads.size
        val normals = IntArray(count)
        val baked = BooleanArray(count)
        var anyBaked = false
        for (i in 0 until count) {
            val quad = quads[i]
            if (!expandable(quad)) return Mesh(MeshPool.generation, -1, noSprites, false)
            val bakedNormal = bakedNormal(quad) ?: return Mesh(MeshPool.generation, -1, noSprites, false)
            val sodiumNormal = sodiumNormal(quad) ?: return Mesh(MeshPool.generation, -1, noSprites, false)
            // One word holds the normal for both paths: Sodium's, which is the baked one wherever there is one.
            if (bakedNormal != 0 && accurateNormal != null && sodiumNormal != bakedNormal) {
                return Mesh(MeshPool.generation, -1, noSprites, false)
            }
            normals[i] = if (accurateNormal != null) sodiumNormal else bakedNormal
            baked[i] = bakedNormal != 0
            if (baked[i]) anyBaked = true
        }
        val sprites = ArrayList<TextureAtlasSprite>(1)
        var address = MeshPool.appendAddress(count)
        for (i in 0 until count) {
            val quad = quads[i]
            for (v in 0 until 4) {
                val position = quad.position(v)
                val uv = quad.packedUV(v)
                MemoryUtil.memPutFloat(address, position.x())
                MemoryUtil.memPutFloat(address + 4, position.y())
                MemoryUtil.memPutFloat(address + 8, position.z())
                MemoryUtil.memPutFloat(address + 12, UVPair.unpackU(uv))
                MemoryUtil.memPutFloat(address + 16, UVPair.unpackV(uv))
                address += 20
            }
            val material = quad.materialInfo()
            val tint = if (material.isTinted) material.tintIndex() else MeshPool.ITEM_UNTINTED
            MemoryUtil.memPutInt(
                address,
                quad.direction().ordinal or (tint shl MeshPool.ITEM_TINT_SHIFT) or
                    (material.lightEmission() shl MeshPool.ITEM_EMISSION_SHIFT) or
                    (if (baked[i]) MeshPool.ITEM_BAKED_NORMAL else 0)
            )
            MemoryUtil.memPutInt(address + 4, normals[i])
            address += 8
            val sprite = material.sprite()
            if (sprites.none { it === sprite }) sprites += sprite
        }
        quadCount += count
        return Mesh(MeshPool.generation, MeshPool.commit(count), sprites.toTypedArray(), anyBaked)
    }

    /**
     * Whether the expand pass can write [quad]: tint index and emission fit the mesh, and NeoForge's baked colours
     * (each vertex's colour times them in `putBakedQuad`) are white.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun expandable(quad: BakedQuad): Boolean {
        val material = quad.materialInfo()
        if (material.isTinted && material.tintIndex() !in 0 until MAX_TINTS) return false
        if (material.lightEmission() !in 0..15) return false
        /*? if neoforge {*/
        /*for (v in 0 until 4) if (quad.bakedColors().color(v) != -1) return false
        *//*?}*/
        return true
    }

    /**
     * The normal NeoForge's `putBakedQuad` takes from [quad] instead of its face's (`applyBakedNormals`; NeoForge's
     * `FaceBakery` gives every quad one): the packed normal if all four vertices have the same, 0 if the quad has none
     * (and on Fabric), null if they differ.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun bakedNormal(quad: BakedQuad): Int? {
        /*? if neoforge {*/
        /*val normals = quad.bakedNormals()
        val first = normals.normal(0)
        for (v in 1 until 4) if (normals.normal(v) != first) return null
        return if (net.neoforged.neoforge.client.model.quad.BakedNormals.isUnspecified(first)) 0 else first
        *//*?}*/
        return 0
    }

    /**
     * The packed normal Sodium's `BakedModelEncoder` transforms for [quad] (`getAccurateNormal`), 0 without Sodium;
     * null if its four vertices differ or Sodium has a colour other than white for one (the expand pass has one
     * normal per quad and no colour of the quad's).
     */
    private fun sodiumNormal(quad: BakedQuad): Int? {
        val normal = accurateNormal ?: return 0
        val color = sodiumColor ?: return 0
        val first = normal.invoke(quad, 0) as Int
        for (v in 0 until 4) {
            if (v > 0 && normal.invoke(quad, v) as Int != first) return null
            if (color.invoke(quad, v) as Int != -1) return null
        }
        return first
    }

    private fun sodiumMethod(name: String): MethodHandle? {
        if (!sodium) return null
        return runCatching {
            val view = Class.forName("net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView")
            val int = Int::class.javaPrimitiveType
            MethodHandles.publicLookup().findVirtual(view, name, MethodType.methodType(int, int))
                .asType(MethodType.methodType(Any::class.java, Any::class.java, int))
        }.getOrNull()
    }
}
