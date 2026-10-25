package dev.nyon.mobium.mixins;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.nyon.mobium.parts.PoseNormals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Module 2: whether a pose's normal matrix needs normalising (read-only). */
@Mixin(PoseStack.Pose.class)
public interface PoseAccessor extends PoseNormals {
    @Override
    @Accessor("trustedNormals")
    boolean mobium$trustedNormals();
}
