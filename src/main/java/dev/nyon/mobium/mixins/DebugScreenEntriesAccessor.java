package dev.nyon.mobium.mixins;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Registers Mobium's F3 entry on Fabric, which has no API for debug screen entries (NeoForge uses
 * {@code RegisterDebugEntriesEvent} instead). Adds an entry to vanilla's registry map; nothing else changes.
 */
@Mixin(DebugScreenEntries.class)
public interface DebugScreenEntriesAccessor {
    @Invoker("register")
    static Identifier mobium$register(Identifier identifier, DebugScreenEntry entry) {
        throw new AssertionError();
    }
}
