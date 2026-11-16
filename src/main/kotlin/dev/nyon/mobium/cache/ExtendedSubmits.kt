package dev.nyon.mobium.cache

import net.minecraft.client.renderer.feature.submit.SubmitNode

/*? if fabric && <26.4 {*/
import net.fabricmc.fabric.api.client.renderer.v1.render.submit.ExtendedBlockModelSubmit
/*?}*/

/**
 * Submit types that loader APIs add next to vanilla's. On Fabric for 26.3, the renderer API (part of Fabric API, which
 * Mobium requires) turns every block model whose model emits through its mesh API into an `ExtendedBlockModelSubmit`
 * (item frames' frames, block displays), drawn by the renderer implementation's feature renderer (Sodium or Indigo).
 * That renderer is a `RenderTypeFeatureRenderer` like vanilla's, so [Capture] can let it write the vertices. Fabric
 * API for 26.4 has no renderer API: block models there are vanilla's submits.
 */
internal object ExtendedSubmits {
    /** True for a block model submit of the loader's renderer API without a breaking decal. */
    fun isPlainBlockModel(submit: SubmitNode): Boolean {
        /*? if fabric && <26.4 {*/
        return submit is ExtendedBlockModelSubmit && submit.sheetedDecalPose() == null
        /*?} else {*/
        /*return false
        *//*?}*/
    }
}
