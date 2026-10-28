package dev.nyon.mobium.mixins;

import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets {@code /mobium benchmark} set the render and entity distance for its runs and put them back without switching
 * the graphics preset to Custom, as applying a preset does ({@code Options.applyGraphicsPreset}). Reads and writes the
 * flag only; nothing else changes.
 */
@Mixin(Options.class)
public interface OptionsAccessor {
    @Accessor("isApplyingGraphicsPreset")
    boolean mobium$isApplyingGraphicsPreset();

    @Accessor("isApplyingGraphicsPreset")
    void mobium$setApplyingGraphicsPreset(boolean applying);
}
