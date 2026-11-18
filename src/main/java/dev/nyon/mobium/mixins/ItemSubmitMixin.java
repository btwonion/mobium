package dev.nyon.mobium.mixins;

import dev.nyon.mobium.offload.PartSubmit;
import dev.nyon.mobium.offload.PartTransform;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers whether an item submit came from an entity type excluded from vertex offload (config
 * {@code offload.excludedEntities}, e.g. {@code minecraft:item} for dropped items). Adds a field only; no behaviour
 * change.
 */
@Mixin(ItemFeatureRenderer.Submit.class)
public class ItemSubmitMixin implements PartSubmit {
    @Unique
    private boolean mobium$partsExcluded;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void mobium$rememberExclusion(CallbackInfo ci) {
        this.mobium$partsExcluded = PartTransform.submittingExcluded;
    }

    @Override
    public boolean mobium$partsExcluded() {
        return this.mobium$partsExcluded;
    }
}
