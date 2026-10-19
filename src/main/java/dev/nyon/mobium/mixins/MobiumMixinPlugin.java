package dev.nyon.mobium.mixins;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/*? if fabric {*/
import net.fabricmc.loader.api.FabricLoader;
/*?} else {*/
/*import net.neoforged.fml.loading.FMLLoader;
*//*?}*/

/**
 * Applies compat mixins only when the mod they target is installed. Compat mixins live in
 * {@code dev.nyon.mobium.mixins.compat.<package>}, and {@link #COMPAT_PACKAGES} names the mod id each package needs.
 * All other mixins always apply.
 */
public class MobiumMixinPlugin implements IMixinConfigPlugin {
    private static final String COMPAT_PREFIX = "dev.nyon.mobium.mixins.compat.";
    private static final Map<String, String> COMPAT_PACKAGES = Map.of(
        "sodium", "sodium",
        "iris", "iris",
        "emf", "entity_model_features",
        "etf", "entity_texture_features"
    );

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.startsWith(COMPAT_PREFIX)) return true;

        String rest = mixinClassName.substring(COMPAT_PREFIX.length());
        int dot = rest.indexOf('.');
        String modId = dot < 0 ? null : COMPAT_PACKAGES.get(rest.substring(0, dot));
        return modId != null && isModLoaded(modId);
    }

    private static boolean isModLoaded(String modId) {
        /*? if fabric {*/
        return FabricLoader.getInstance().isModLoaded(modId);
        /*?} else {*/
        /*return FMLLoader.getCurrent().getLoadingModList().getModFileById(modId) != null;
        *//*?}*/
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
