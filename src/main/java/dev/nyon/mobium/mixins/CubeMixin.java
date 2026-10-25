package dev.nyon.mobium.mixins;

import dev.nyon.mobium.parts.PartMeshHolder;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Module 2: where the mesh pool keeps a cube's mesh (fields only; no behaviour change). */
@Mixin(ModelPart.Cube.class)
public class CubeMixin implements PartMeshHolder {
    @Unique
    private int mobium$meshGeneration;

    @Unique
    private int mobium$meshQuad;

    @Override
    public int mobium$meshGeneration() {
        return this.mobium$meshGeneration;
    }

    @Override
    public int mobium$meshQuad() {
        return this.mobium$meshQuad;
    }

    @Override
    public void mobium$setMesh(int generation, int quad) {
        this.mobium$meshGeneration = generation;
        this.mobium$meshQuad = quad;
    }
}
