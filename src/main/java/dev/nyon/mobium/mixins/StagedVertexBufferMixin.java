package dev.nyon.mobium.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.nyon.mobium.parts.PartBatch;
import dev.nyon.mobium.parts.PartDraw;
import dev.nyon.mobium.parts.PartStaged;
import dev.nyon.mobium.parts.PartTarget;
import dev.nyon.mobium.parts.PartTransform;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Module 2: tags each builder the staged buffer opens with its draw, so that a reservation knows where its vertices
 * land, and runs the expand pass right after {@code upload()} copied the vertices to the GPU and before anything is
 * drawn from them. Without reservations the pass does nothing; vanilla's upload itself is unchanged.
 */
@Mixin(StagedVertexBuffer.class)
public class StagedVertexBufferMixin implements PartStaged {
    @Shadow
    private @Nullable GpuBuffer currentVertexBuffer;

    @Unique
    private @Nullable PartBatch mobium$batch;

    @ModifyExpressionValue(method = "getVertexBuilder", at = @At(value = "NEW", target = "com/mojang/blaze3d/vertex/BufferBuilder"))
    private BufferBuilder mobium$tagBuilder(BufferBuilder builder, @Local(argsOnly = true) StagedVertexBuffer.Draw draw) {
        ((PartTarget) builder).mobium$tag((StagedVertexBuffer) (Object) this, draw, ((PartDraw) draw).mobium$vertexBufferSize());
        return builder;
    }

    @Inject(method = "upload", at = @At("RETURN"))
    private void mobium$expand(CallbackInfo ci) {
        PartTransform.afterUpload((StagedVertexBuffer) (Object) this);
    }

    @Inject(method = "endDraw", at = @At("HEAD"))
    private void mobium$discard(CallbackInfo ci) {
        PartTransform.discard((StagedVertexBuffer) (Object) this);
    }

    @Override
    public @Nullable PartBatch mobium$batch() {
        return this.mobium$batch;
    }

    @Override
    public void mobium$setBatch(PartBatch batch) {
        this.mobium$batch = batch;
    }

    @Override
    public @Nullable GpuBuffer mobium$vertexBuffer() {
        return this.currentVertexBuffer;
    }
}
