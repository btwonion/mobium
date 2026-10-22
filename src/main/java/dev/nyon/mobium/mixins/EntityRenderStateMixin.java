package dev.nyon.mobium.mixins;

import dev.nyon.mobium.cache.EntityStateSource;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Static cache (Module 1): remembers which entity a render state was extracted from ({@link EntityStateSource}), so the
 * cache can find the entity's entry when the state is submitted. Adds a field; no behaviour change.
 */
@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements EntityStateSource {
    @Unique
    private @Nullable Entity mobium$entity;

    @Override
    public @Nullable Entity mobium$entity() {
        return this.mobium$entity;
    }

    @Override
    public void mobium$setEntity(@Nullable Entity entity) {
        this.mobium$entity = entity;
    }
}
