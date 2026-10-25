package dev.nyon.mobium.mixins;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Module 2: a part's cubes, for EMF's parts whose compile override Mobium wraps (read-only). */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {
    @Accessor("cubes")
    List<ModelPart.Cube> mobium$cubes();
}
