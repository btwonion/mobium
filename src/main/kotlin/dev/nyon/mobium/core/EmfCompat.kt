package dev.nyon.mobium.core

import net.minecraft.client.model.Model
import traben.entity_model_features.models.parts.EMFModelPartRoot

/**
 * Calls into Entity Model Features (3.3.12). Only call these after checking `Compat.isLoaded(CompatMod.EMF)`.
 */
object EmfCompat {
    /**
     * True when EMF replaced the model's root with a custom model that animates
     * (`EMFModelPartRoot.containsCustomAnims`, e.g. a resource pack's chest that breathes): a cached picture would
     * freeze it.
     */
    fun isAnimated(model: Model<*>): Boolean = (model.root() as? EMFModelPartRoot)?.containsCustomAnims == true
}
