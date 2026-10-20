package dev.nyon.mobium.mixins;

import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Static cache (Module 1): vanilla's own test for whether glowing sign text gets its outline (camera within 16 blocks,
 * or scoping), so that a cached sign is captured again when the answer changes. No behaviour change.
 */
@Mixin(AbstractSignRenderer.class)
public interface AbstractSignRendererInvoker {
    @Invoker("isOutlineVisible")
    static boolean mobium$isOutlineVisible(BlockPos pos) {
        throw new AssertionError();
    }
}
