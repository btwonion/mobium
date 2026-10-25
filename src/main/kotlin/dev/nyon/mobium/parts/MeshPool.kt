package dev.nyon.mobium.parts

import net.minecraft.client.model.geom.ModelPart
import org.lwjgl.system.MemoryUtil

/**
 * Module 2's cube meshes: the first time a cube is drawn, its faces are written once in the cube's local space, one
 * quad of [QUAD_WORDS] words each, in the layout of the path that would have drawn it (`expand_parts.comp`):
 * - vanilla and EMF cubes as `Cube.compile` writes them: four vertices `x/16, y/16, z/16, u, v` from the polygons, in
 *   polygon order, then the face's `Direction` ordinal;
 * - vanilla cubes with Sodium as Sodium's `EntityRenderer.renderCuboid` writes them from its `ModelCuboid`
 *   ([SodiumCubes]): the corner origin and sizes, four vertices `corner, u, v`, in Sodium's face order, then the
 *   `Direction` ordinal with [SODIUM_LAYOUT] set.
 * The cube remembers where (`PartMeshHolder`, so a lookup is a few field reads); new quads collect in [pending] until
 * the expand pass uploads them. EMF's parts are cubes too. Render thread only.
 *
 * A cube that cannot be expanded (a polygon with other than four vertices, a normal that is not one of the six axis
 * directions, no `ModelCuboid`) is remembered as such and stays on the vanilla path. [clear] (resource reload, level
 * change) starts the pool over: every cube is written again when next drawn.
 */
object MeshPool {
    const val QUAD_WORDS = 21

    /** Set in a quad's last word when the quad has Sodium's layout. */
    const val SODIUM_LAYOUT = 0x100
    private const val QUAD_BYTES = QUAD_WORDS * 4
    private const val INITIAL_PENDING_QUADS = 4096

    /** Bumped by [clear]; a cube's mesh is valid while its generation matches. Starts at 1 (0 means never seen). */
    @JvmField
    var generation = 1

    /** Quads in the pool, uploaded or not. */
    @JvmField
    var quadCount = 0

    /** The first quad of [pending]: quads below it are on the GPU already. */
    @JvmField
    var uploadedQuads = 0

    private var pending = MemoryUtil.nmemAlloc(INITIAL_PENDING_QUADS.toLong() * QUAD_BYTES)
    private var pendingCapacity = INITIAL_PENDING_QUADS

    /** Address of the quads not uploaded yet ([quadCount] − [uploadedQuads] of them). */
    val pendingAddress: Long
        get() = pending

    /**
     * The first quad of [cube]'s mesh, writing it on first sight in Sodium's layout if [sodium]; -1 if the cube stays
     * on the vanilla path. A cube's layout never changes: its class and Sodium's presence decide it.
     */
    fun firstQuad(cube: ModelPart.Cube, sodium: Boolean): Int {
        val holder = cube as PartMeshHolder
        if (holder.`mobium$meshGeneration`() == generation) return holder.`mobium$meshQuad`()
        val quad = if (sodium) writeSodium(cube) else write(cube)
        holder.`mobium$setMesh`(generation, quad, if (quad < 0) 0 else quadCount - quad)
        return quad
    }

    /** The number of quads of [cube]'s mesh; only valid after [firstQuad] returned a quad. */
    fun quads(cube: ModelPart.Cube): Int = (cube as PartMeshHolder).`mobium$meshQuads`()

    /** Called after the pending quads were uploaded. */
    fun uploaded() {
        uploadedQuads = quadCount
    }

    fun clear() {
        generation++
        quadCount = 0
        uploadedQuads = 0
    }

    private fun write(cube: ModelPart.Cube): Int {
        val polygons = cube.polygons
        for (polygon in polygons) {
            if (polygon.vertices().size != 4 || direction(polygon) < 0) return -1
        }
        val first = quadCount
        ensurePending(first - uploadedQuads + polygons.size)
        var address = pending + (first - uploadedQuads).toLong() * QUAD_BYTES
        for (polygon in polygons) {
            for (vertex in polygon.vertices()) {
                // Vertex.worldX() and EMF's EMFCube.compile both divide by 16 per vertex.
                MemoryUtil.memPutFloat(address, vertex.x() / 16.0f)
                MemoryUtil.memPutFloat(address + 4, vertex.y() / 16.0f)
                MemoryUtil.memPutFloat(address + 8, vertex.z() / 16.0f)
                MemoryUtil.memPutFloat(address + 12, vertex.u())
                MemoryUtil.memPutFloat(address + 16, vertex.v())
                address += 20
            }
            MemoryUtil.memPutInt(address, direction(polygon))
            address += 4
        }
        quadCount = first + polygons.size
        return first
    }

    private fun writeSodium(cube: ModelPart.Cube): Int {
        val cuboid = SodiumCubes.cuboid(cube) ?: return -1
        var faces = 0
        for (face in 0 until 6) if (cuboid.shouldDrawFace(face)) faces++
        val first = quadCount
        ensurePending(first - uploadedQuads + faces)
        var address = pending + (first - uploadedQuads).toLong() * QUAD_BYTES
        for (face in 0 until 6) {
            if (!cuboid.shouldDrawFace(face)) continue
            MemoryUtil.memPutFloat(address, cuboid.originX)
            MemoryUtil.memPutFloat(address + 4, cuboid.originY)
            MemoryUtil.memPutFloat(address + 8, cuboid.originZ)
            MemoryUtil.memPutFloat(address + 12, cuboid.sizeX)
            MemoryUtil.memPutFloat(address + 16, cuboid.sizeY)
            MemoryUtil.memPutFloat(address + 20, cuboid.sizeZ)
            for (v in 0 until 4) {
                // ModelCuboid.textures packs (u, v) as Int2: u's bits low, v's high (little endian).
                val texture = cuboid.textures[face * 4 + v]
                MemoryUtil.memPutInt(address + 24 + v * 12L, cuboid.positions[face * 4 + v])
                MemoryUtil.memPutInt(address + 28 + v * 12L, texture.toInt())
                MemoryUtil.memPutInt(address + 32 + v * 12L, (texture ushr 32).toInt())
            }
            MemoryUtil.memPutInt(address + 72, 0)
            MemoryUtil.memPutInt(address + 76, 0)
            MemoryUtil.memPutInt(address + 80, SodiumCubes.NORMAL_DIRECTIONS[cuboid.normals[face]] or SODIUM_LAYOUT)
            address += QUAD_BYTES
        }
        quadCount = first + faces
        return first
    }

    private fun ensurePending(quads: Int) {
        if (quads <= pendingCapacity) return
        var capacity = pendingCapacity
        while (capacity < quads) capacity *= 2
        pending = MemoryUtil.nmemRealloc(pending, capacity.toLong() * QUAD_BYTES)
        pendingCapacity = capacity
    }

    /**
     * The `Direction` ordinal (DOWN, UP, NORTH, SOUTH, WEST, EAST) whose unit vector is exactly the polygon's normal,
     * or -1. Vanilla and EMF polygons take theirs from `Direction.getUnitVec3f()`.
     */
    private fun direction(polygon: ModelPart.Polygon): Int {
        val normal = polygon.normal()
        val x = normal.x()
        val y = normal.y()
        val z = normal.z()
        return when {
            x == 0f && z == 0f && y == -1f -> 0
            x == 0f && z == 0f && y == 1f -> 1
            x == 0f && y == 0f && z == -1f -> 2
            x == 0f && y == 0f && z == 1f -> 3
            y == 0f && z == 0f && x == -1f -> 4
            y == 0f && z == 0f && x == 1f -> 5
            else -> -1
        }
    }
}
