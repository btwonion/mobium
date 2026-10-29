package dev.nyon.mobium.core

/*? if fabric {*/
import net.fabricmc.loader.api.FabricLoader
/*?} else {*/
/*import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLLoader
*//*?}*/

/** Mods Mobium has to cooperate with, by their mod id (identical on Fabric and NeoForge). */
enum class CompatMod(val modId: String, val displayName: String) {
    SODIUM("sodium", "Sodium"),
    IRIS("iris", "Iris"),
    EMF("entity_model_features", "Entity Model Features"),
    ETF("entity_texture_features", "Entity Texture Features"),
    ENTITY_CULLING("entityculling", "Entity Culling"),
    MOD_MENU("modmenu", "Mod Menu")
}

/**
 * Mods that do one of Mobium's jobs; while one is installed, the modules it overlaps take the vanilla path
 * (docs/compat.md). Mod ids looked up in their sources on 20 Oct 2026 (`docs/compat.md`).
 */
enum class OverlappingMod(val modId: String, val displayName: String, val modules: Set<Module>) {
    /** Enhanced Block Entities and its "Reloaded" port (same id) turn block entities into baked block models. */
    ENHANCED_BLOCK_ENTITIES("enhancedblockentities", "Enhanced Block Entities", setOf(Module.STATIC_CACHE)),

    /** CrankShaft's Vanillate draws chests, minecarts and other vanilla visuals through instancing. */
    VANILLATE("vanillate", "Vanillate (CrankShaft)", setOf(Module.STATIC_CACHE, Module.PART_TRANSFORM)),

    /** Accelerated Rendering transforms and caches entity vertices on the GPU (no 26.x build as of Oct 2026). */
    ACCELERATED_RENDERING(
        "acceleratedrendering", "Accelerated Rendering",
        setOf(Module.STATIC_CACHE, Module.PART_TRANSFORM, Module.ANIMATION_LOD)
    )
}

/**
 * Mods that hook the same rendering paths as Mobium in a way not tested with it; while one is installed, the modules
 * it may break take the vanilla path until a test shows otherwise (docs/compat.md).
 */
enum class UntestedMod(val modId: String, val displayName: String, val modules: Set<Module>) {
    /**
     * Vitrail draws OptiFine-format shader packs on the Vulkan renderer and hooks block entity and entity submission,
     * `StagedVertexBuffer` and `BufferBuilder` with an entity vertex layout of its own (docs/vanilla-notes.md §22).
     */
    VITRAIL("vitrail", "Vitrail", setOf(Module.STATIC_CACHE, Module.PART_TRANSFORM))
}

/** Detects the installed compat, overlapping and untested mods once at startup. */
object Compat {
    /** The loader this jar was built for. */
    /*? if fabric {*/
    const val LOADER = "fabric"
    /*?} else {*/
    /*const val LOADER = "neoforge"
    *//*?}*/

    private val versions = HashMap<CompatMod, String>()
    private val overlapping = ArrayList<OverlappingMod>()
    private val untestedMods = ArrayList<UntestedMod>()

    fun detect() {
        versions.clear()
        CompatMod.entries.forEach { mod -> modVersion(mod.modId)?.let { versions[mod] = it } }
        overlapping.clear()
        OverlappingMod.entries.filterTo(overlapping) { modVersion(it.modId) != null }
        untestedMods.clear()
        UntestedMod.entries.filterTo(untestedMods) { modVersion(it.modId) != null }
    }

    fun isLoaded(mod: CompatMod): Boolean = mod in versions

    /** Per module, the first installed mod that overlaps it. */
    fun overlaps(): Map<Module, String> {
        val result = HashMap<Module, String>()
        for (mod in overlapping) for (module in mod.modules) result.putIfAbsent(module, mod.displayName)
        return result
    }

    /** Per module, the first installed mod that is untested with it. */
    fun untested(): Map<Module, String> {
        val result = HashMap<Module, String>()
        for (mod in untestedMods) for (module in mod.modules) result.putIfAbsent(module, mod.displayName)
        return result
    }

    fun describe(): String {
        val parts = CompatMod.entries.filter(::isLoaded).map { "${it.displayName} ${versions[it]}" } +
            overlapping.map { "${it.displayName} (overlaps ${it.modules.joinToString { module -> module.key }})" } +
            untestedMods.map { "${it.displayName} (untested with ${it.modules.joinToString { module -> module.key }})" }
        return if (parts.isEmpty()) "none" else parts.joinToString()
    }

    /** True in a development environment (dev client, gametests), where the debug commands exist. */
    val isDevelopment: Boolean by lazy {
        /*? if fabric {*/
        FabricLoader.getInstance().isDevelopmentEnvironment
        /*?} else {*/
        /*!FMLLoader.getCurrent().isProduction
        *//*?}*/
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
