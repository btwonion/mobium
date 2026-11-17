package dev.nyon.mobium.mixins;

import dev.nyon.mobium.gpu.gl.GlBufferHandle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Vertex offload: an OpenGL buffer's GL name for the expand pass. 26.4 makes {@code GlBuffer} package-private while its
 * {@code handle()} stays public, so the class is named by string. Read-only; no behaviour change.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlBuffer")
public abstract class GlBufferMixin implements GlBufferHandle {
    @Shadow
    public abstract int handle();

    @Override
    public int mobium$handle() {
        return this.handle();
    }
}
