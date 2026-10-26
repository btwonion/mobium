package dev.nyon.mobium.parts

import net.minecraft.client.model.geom.ModelPart
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Sodium's own description of a vanilla cube (`ModelCuboid`: corner origin and sizes divided by 16, its UVs, faces and
 * normals), which Sodium's `CubeMixin` keeps in a field it adds to every `ModelPart.Cube` and draws from instead of the
 * cube's polygons (docs/vanilla-notes.md §16). Read by reflection: on NeoForge Sodium's classes are inside a nested
 * jar and only its API is on Mobium's compile classpath. The field is found by type, so a renamed one still works;
 * without it, vanilla cubes stay on Sodium's path. [read] fills the fields below; only call it with Sodium installed,
 * on the render thread, once per cube (the mesh pool keeps the result).
 */
object SodiumCubes {
    private class Access(
        val cuboid: Field,
        val origin: Array<Field>,
        val size: Array<Field>,
        val normals: Field,
        val positions: Field,
        val textures: Field,
        val shouldDrawFace: Method
    )

    private val access: Access? = runCatching {
        val type = Class.forName("net.caffeinemc.mods.sodium.client.render.immediate.model.ModelCuboid")
        val cuboid = ModelPart.Cube::class.java.declaredFields.first { it.type == type }.apply { isAccessible = true }
        fun field(name: String) = type.getField(name)
        Access(
            cuboid,
            arrayOf(field("originX"), field("originY"), field("originZ")),
            arrayOf(field("sizeX"), field("sizeY"), field("sizeZ")),
            field("normals"),
            field("positions"),
            field("textures"),
            type.getMethod("shouldDrawFace", Int::class.javaPrimitiveType)
        )
    }.getOrNull()

    val available: Boolean
        get() = access != null

    /** Corner origin x, y, z, then sizes x, y, z, of the cube last [read]. */
    @JvmField
    val bounds = FloatArray(6)

    /** Per Sodium face (DOWN, UP, EAST, NORTH, WEST, SOUTH): drawn, its normal index, 4 corners, 4 packed UVs. */
    @JvmField
    val drawFace = BooleanArray(6)

    @JvmField
    val normals = IntArray(6)

    @JvmField
    val positions = IntArray(24)

    @JvmField
    val textures = LongArray(24)

    /** Reads [cube]'s `ModelCuboid` into the fields above; false if it has none. */
    fun read(cube: ModelPart.Cube): Boolean {
        val access = access ?: return false
        val cuboid = access.cuboid.get(cube) ?: return false
        for (i in 0 until 3) {
            bounds[i] = access.origin[i].getFloat(cuboid)
            bounds[3 + i] = access.size[i].getFloat(cuboid)
        }
        (access.normals.get(cuboid) as IntArray).copyInto(normals)
        (access.positions.get(cuboid) as IntArray).copyInto(positions)
        (access.textures.get(cuboid) as LongArray).copyInto(textures)
        for (face in 0 until 6) drawFace[face] = access.shouldDrawFace.invoke(cuboid, face) as Boolean
        return true
    }

    /** Sodium's face normal index (`EntityRenderer`'s CUBE_FACE_NORMAL order) to the `Direction` ordinal. */
    @JvmField
    val NORMAL_DIRECTIONS = intArrayOf(0, 1, 5, 2, 4, 3)
}
