package dev.nyon.mobium.mixins;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.nyon.mobium.parts.PartTarget;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Module 2: remembers which staged draw a builder writes for, and reserves vertices for the GPU expand pass. Changes
 * nothing by itself; a reservation only happens for model parts Module 2 takes over, otherwise vanilla writes as
 * before.
 */
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin implements PartTarget {
    @Shadow
    @Final
    private ByteBufferBuilder buffer;

    @Shadow
    private long vertexPointer;

    @Shadow
    private int vertices;

    @Shadow
    @Final
    private VertexFormat format;

    @Shadow
    @Final
    private PrimitiveTopology primitiveTopology;

    @Shadow
    @Final
    private int vertexSize;

    @Shadow
    private int elementsToFill;

    @Shadow
    private boolean building;

    @Unique
    private StagedVertexBuffer mobium$staged;

    @Unique
    private StagedVertexBuffer.Draw mobium$draw;

    @Unique
    private int mobium$drawBytesBefore;

    @Shadow
    private void endLastVertex() {
        throw new AssertionError();
    }

    @Override
    public StagedVertexBuffer mobium$staged() {
        return this.mobium$staged;
    }

    @Override
    public StagedVertexBuffer.Draw mobium$draw() {
        return this.mobium$draw;
    }

    @Override
    public int mobium$drawBytesBefore() {
        return this.mobium$drawBytesBefore;
    }

    @Override
    public void mobium$tag(StagedVertexBuffer staged, StagedVertexBuffer.Draw draw, int drawBytesBefore) {
        this.mobium$staged = staged;
        this.mobium$draw = draw;
        this.mobium$drawBytesBefore = drawBytesBefore;
    }

    @Override
    public VertexFormat mobium$format() {
        return this.format;
    }

    @Override
    public PrimitiveTopology mobium$topology() {
        return this.primitiveTopology;
    }

    @Override
    public boolean mobium$building() {
        return this.building;
    }

    @Override
    public int mobium$vertexCount() {
        return this.vertices;
    }

    // What beginVertex does for each vertex, at once for all of them and without writing any element.
    @Override
    public int mobium$reserve(int count) {
        this.endLastVertex();
        int offset = this.vertices * this.vertexSize;
        long pointer = this.buffer.reserve(count * this.vertexSize);
        this.vertices += count;
        this.vertexPointer = pointer + (long) (count - 1) * this.vertexSize;
        this.elementsToFill = 0;
        return offset;
    }
}
