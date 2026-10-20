package dev.nyon.mobium.mixins;

import dev.nyon.mobium.debug.Sections;
import dev.nyon.mobium.debug.TimedSubmit;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers whether a model submit came from the block entity submit loop, so that the prepare-stage timers can split
 * posing and tessellation by entities and block entities. Adds a field only; no behaviour change.
 */
@Mixin(ModelFeatureRenderer.Submit.class)
public class ModelSubmitMixin implements TimedSubmit {
    @Unique
    private boolean mobium$blockEntity;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void mobium$rememberOrigin(CallbackInfo ci) {
        this.mobium$blockEntity = Sections.submittingBlockEntities;
    }

    @Override
    public boolean mobium$isBlockEntity() {
        return this.mobium$blockEntity;
    }
}
