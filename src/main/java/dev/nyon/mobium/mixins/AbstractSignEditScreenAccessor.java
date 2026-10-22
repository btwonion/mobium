package dev.nyon.mobium.mixins;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Static cache (Module 1): tells which sign is being edited, so that it takes the vanilla path while the edit screen is
 * open. Read-only; no behaviour change.
 */
@Mixin(AbstractSignEditScreen.class)
public interface AbstractSignEditScreenAccessor {
    @Accessor("sign")
    SignBlockEntity mobium$sign();
}
