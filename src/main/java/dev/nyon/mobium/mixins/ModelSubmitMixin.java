package dev.nyon.mobium.mixins;

import dev.nyon.mobium.debug.Sections;
import dev.nyon.mobium.debug.TimedSubmit;
import dev.nyon.mobium.parts.PartSubmit;
import dev.nyon.mobium.parts.PartTransform;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers whether a model submit came from the block entity submit loop, so that the prepare-stage timers can split
 * posing and tessellation by entities and block entities, and whether it came from an entity type excluded from Module
 * 2 (config {@code parts.excludedEntities}). Adds fields only; no behaviour change.
 */
@Mixin(ModelFeatureRenderer.Submit.class)
public class ModelSubmitMixin implements TimedSubmit, PartSubmit {
    @Unique
    private boolean mobium$blockEntity;

    @Unique
    private boolean mobium$partsExcluded;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void mobium$rememberOrigin(CallbackInfo ci) {
        this.mobium$blockEntity = Sections.submittingBlockEntities;
        this.mobium$partsExcluded = PartTransform.submittingExcluded;
    }

    @Override
    public boolean mobium$partsExcluded() {
        return this.mobium$partsExcluded;
    }

    @Override
    public boolean mobium$isBlockEntity() {
        return this.mobium$blockEntity;
    }
}
