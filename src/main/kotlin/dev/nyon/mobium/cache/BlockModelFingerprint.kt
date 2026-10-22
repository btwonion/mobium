package dev.nyon.mobium.cache

import dev.nyon.mobium.mixins.BlockModelRenderStateAccessor
import net.minecraft.client.renderer.block.BlockModelRenderState
import org.joml.Matrix4f

/**
 * What a block model state draws (`BlockModelRenderState.submit`), kept so that the state vanilla extracts in a later
 * frame can be compared with it without allocating: the model parts, the transformation, the render type, the tints and
 * the block light. Block models are resolved again every frame (`BlockModelResolver.update`); the parts come from the
 * baked model, so they stay the same objects until a resource reload. Special renderers (a block display of a chest,
 * a head or a banner) are not supported, like in [ItemFingerprint].
 */
class BlockModelFingerprint {
    private var parts = arrayOfNulls<Any>(0)
    private var hasParts = false
    private var hasTransformation = false
    private val transformation = Matrix4f()
    private var renderType: Any? = null
    private var tints = IntArray(0)
    private var blockLight = 0

    /** Copies what [state] draws; false when it uses a special renderer, which the cache does not take. */
    fun store(state: BlockModelRenderState): Boolean {
        val access = state as BlockModelRenderStateAccessor
        if (access.`mobium$specialRenderer`() != null) return false
        val modelParts = access.`mobium$modelParts`()
        hasParts = modelParts != null
        parts = modelParts?.toTypedArray() ?: arrayOfNulls(0)
        val matrix = access.`mobium$transformation`()
        hasTransformation = matrix != null
        if (matrix != null) transformation.set(matrix) else transformation.identity()
        renderType = access.`mobium$renderType`()
        tints = access.`mobium$tintLayers`()?.toIntArray() ?: IntArray(0)
        blockLight = state.blockLightCoords
        return true
    }

    /** True when [state] draws exactly what [store] kept. */
    fun matches(state: BlockModelRenderState): Boolean {
        val access = state as BlockModelRenderStateAccessor
        if (access.`mobium$specialRenderer`() != null || state.blockLightCoords != blockLight ||
            access.`mobium$renderType`() !== renderType
        ) {
            return false
        }
        val modelParts = access.`mobium$modelParts`()
        if ((modelParts != null) != hasParts) return false
        if (modelParts != null) {
            if (modelParts.size != parts.size) return false
            for (i in parts.indices) if (modelParts[i] !== parts[i]) return false
        }
        val matrix = access.`mobium$transformation`()
        if ((matrix != null) != hasTransformation) return false
        if (matrix != null && !transformation.equals(matrix, 0f)) return false
        val current = access.`mobium$tintLayers`()
        val size = current?.size ?: 0
        if (size != tints.size) return false
        for (t in 0 until size) if (current!!.getInt(t) != tints[t]) return false
        return true
    }
}
