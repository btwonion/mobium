package dev.nyon.mobium.parts

import net.caffeinemc.mods.sodium.client.render.immediate.model.ModelCuboid
import net.minecraft.client.model.geom.ModelPart
import java.lang.reflect.Field

/**
 * Sodium's own description of a vanilla cube (`ModelCuboid`: corner origin and sizes divided by 16, its UVs, faces and
 * normals), which Sodium's `CubeMixin` keeps in a field it adds to every `ModelPart.Cube` and draws from instead of the
 * cube's polygons (docs/vanilla-notes.md §16). Found by type, so a renamed field still works; without it, vanilla
 * cubes stay on Sodium's path. Only use with Sodium installed.
 */
object SodiumCubes {
    private val cuboidField: Field? = runCatching {
        ModelPart.Cube::class.java.declaredFields.firstOrNull { it.type == ModelCuboid::class.java }?.apply { isAccessible = true }
    }.getOrNull()

    val available: Boolean
        get() = cuboidField != null

    fun cuboid(cube: ModelPart.Cube): ModelCuboid? = cuboidField?.get(cube) as ModelCuboid?

    /** Sodium's face normal index (`EntityRenderer`'s CUBE_FACE_NORMAL order) to the `Direction` ordinal. */
    @JvmField
    val NORMAL_DIRECTIONS = intArrayOf(0, 1, 5, 2, 4, 3)
}
