package dev.nyon.mobium.core

/*? if fabric {*/
import net.fabricmc.loader.api.FabricLoader
/*?} else {*/
/*import net.neoforged.fml.ModList
*//*?}*/

/** Mods Mobium has to cooperate with, by their mod id (identical on Fabric and NeoForge). */
enum class CompatMod(val modId: String, val displayName: String) {
    SODIUM("sodium", "Sodium"),
    IRIS("iris", "Iris"),
    EMF("entity_model_features", "Entity Model Features"),
    ETF("entity_texture_features", "Entity Texture Features"),
    MOD_MENU("modmenu", "Mod Menu")
}

/** Detects the installed compat mods once at startup. Shader-pack state and overlapping mods follow in M4. */
object Compat {
    /** The loader this jar was built for. */
    /*? if fabric {*/
    const val LOADER = "fabric"
    /*?} else {*/
    /*const val LOADER = "neoforge"
    *//*?}*/

    private val versions = HashMap<CompatMod, String>()

    fun detect() {
        versions.clear()
        CompatMod.entries.forEach { mod -> modVersion(mod.modId)?.let { versions[mod] = it } }
    }

    fun isLoaded(mod: CompatMod): Boolean = mod in versions

    fun describe(): String {
        if (versions.isEmpty()) return "none"
        return CompatMod.entries.filter(::isLoaded).joinToString { "${it.displayName} ${versions[it]}" }
    }

    /** The installed version of [modId], or null if it is not loaded. */
    fun modVersion(modId: String): String? {
        /*? if fabric {*/
        return FabricLoader.getInstance().getModContainer(modId).map { it.metadata.version.friendlyString }.orElse(null)
        /*?} else {*/
        /*return ModList.get().getModContainerById(modId).map { it.modInfo.version.toString() }.orElse(null)
        *//*?}*/
    }
}
