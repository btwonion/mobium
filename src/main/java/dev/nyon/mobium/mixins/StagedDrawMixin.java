package dev.nyon.mobium.mixins;

import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.nyon.mobium.parts.PartDraw;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Module 2: exposes a staged draw's format, sorting and layout, and gives it a slot in the expand pass's table of
 * draws. Read-only for vanilla's fields; no behaviour change.
 */
@Mixin(StagedVertexBuffer.Draw.class)
public class StagedDrawMixin implements PartDraw {
    @Shadow
    @Final
    private VertexFormat format;

    @Shadow
    @Final
    private @Nullable VertexSorting quadSorting;

    @Shadow
    private int vertexBufferSize;

    @Shadow
    private int vertexOffset;

    @Unique
    private int mobium$slot = -1;

    @Override
    public VertexFormat mobium$format() {
        return this.format;
    }

    @Override
    public boolean mobium$sorted() {
        return this.quadSorting != null;
    }

    @Override
    public int mobium$vertexBufferSize() {
        return this.vertexBufferSize;
    }

    @Override
    public int mobium$vertexOffset() {
        return this.vertexOffset;
    }

    @Override
    public int mobium$slot() {
        return this.mobium$slot;
    }

    @Override
    public void mobium$setSlot(int slot) {
        this.mobium$slot = slot;
    }
}
