package dev.nyon.mobium.cache

import dev.nyon.mobium.mixins.ItemStackRenderStateAccessor
import dev.nyon.mobium.mixins.LayerRenderStateAccessor
import net.minecraft.client.renderer.item.ItemStackRenderState
import org.joml.Matrix4f

/**
 * What an item state draws, kept so that the state vanilla extracts in a later frame can be compared with it without
 * allocating: the display context and, per layer, everything `LayerRenderState.submit` uses (quads, item transform,
 * local transform, glint, tints). Item models are resolved again every frame and some pick another model each time
 * (a compass needle, `RangeSelectItemModel`), which `ItemStackRenderState.isAnimated` does not say, so an item is only
 * drawn from the cache while this still matches. Layers drawn by a special renderer (heads, shields, banners) are not
 * supported: some load their textures later.
 */
class ItemFingerprint {
    private var displayContext: Any? = null
    private var layerCount = 0
    private var quads = arrayOfNulls<Any>(0)
    private var itemTransforms = arrayOfNulls<Any>(0)
    private var foils = arrayOfNulls<Any>(0)
    private var localTransforms = emptyArray<Matrix4f>()
    private var tints = emptyArray<IntArray>()

    /** Copies what [state] draws; false when a layer uses a special renderer, which the cache does not take. */
    fun store(state: ItemStackRenderState): Boolean {
        val access = state as ItemStackRenderStateAccessor
        val count = access.`mobium$activeLayerCount`()
        val layers = access.`mobium$layers`()
        displayContext = access.`mobium$displayContext`()
        layerCount = count
        quads = arrayOfNulls(count)
        itemTransforms = arrayOfNulls(count)
        foils = arrayOfNulls(count)
        localTransforms = Array(count) { Matrix4f() }
        tints = Array(count) { IntArray(0) }
        for (i in 0 until count) {
            val layer = layers[i] as LayerRenderStateAccessor
            if (layer.`mobium$specialRenderer`() != null) return false
            quads[i] = layer.`mobium$quads`()
            itemTransforms[i] = layer.`mobium$itemTransform`()
            foils[i] = layer.`mobium$foilType`()
            localTransforms[i].set(layer.`mobium$localTransform`())
            tints[i] = layer.`mobium$tintLayers`()?.toIntArray() ?: IntArray(0)
        }
        return true
    }

    /** True when [state] draws exactly what [store] kept. */
    fun matches(state: ItemStackRenderState): Boolean {
        val access = state as ItemStackRenderStateAccessor
        if (access.`mobium$activeLayerCount`() != layerCount || access.`mobium$displayContext`() !== displayContext) return false
        val layers = access.`mobium$layers`()
        for (i in 0 until layerCount) {
            val layer = layers[i] as LayerRenderStateAccessor
            if (layer.`mobium$specialRenderer`() != null || layer.`mobium$quads`() !== quads[i] ||
                layer.`mobium$itemTransform`() !== itemTransforms[i] || layer.`mobium$foilType`() !== foils[i] ||
                layer.`mobium$localTransform`() != localTransforms[i]
            ) {
                return false
            }
            val stored = tints[i]
            val current = layer.`mobium$tintLayers`()
            val size = current?.size ?: 0
            if (size != stored.size) return false
            for (t in 0 until size) if (current!!.getInt(t) != stored[t]) return false
        }
        return true
    }
}
