package dev.nyon.mobium.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.nyon.mobium.debug.Sections;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.oit.OitStage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Times the CPU side of the level's feature draws: every phase of the prepared frame goes through {@code executePhase}.
 * Only counted while {@code RenderSystem.isRenderingLevel}, so hand, GUI and picture-in-picture draws are left out;
 * Iris's shadow pass, which runs inside the level render, counts as its own section.
 * Behaviour is unchanged; with timers off the wrapper only calls through.
 */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public class PreparedFrameMixin {
    @WrapMethod(
        method = "executePhase(Lnet/minecraft/client/renderer/feature/phase/FeatureRenderPhase;Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Lnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"
    )
    private void mobium$timeDraw(
        FeatureRenderPhase<?> phase,
        FeatureFrameContext context,
        @Nullable OitStage stage,
        RenderPass renderPass,
        Operation<Void> original
    ) {
        long start = RenderSystem.isRenderingLevel ? Sections.begin() : 0L;
        original.call(phase, context, stage, renderPass);
        Sections.end(Sections.inShadowPass ? Sections.SHADOW_DRAW : Sections.DRAW, start);
    }
}
