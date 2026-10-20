package dev.nyon.mobium.config

import kotlinx.serialization.Serializable
import kotlin.reflect.KMutableProperty0

/**
 * The persisted settings (`config/mobium.json`). Every module has its own switch and its own per-type switches, and
 * every switch only ever selects between Mobium's path and vanilla's: nothing here changes what is drawn.
 *
 * @param enabled is the global switch; when false every module uses the vanilla path.
 * @param cache holds the static cache (Module 1) settings.
 * @param parts holds the GPU part transform (Module 2) settings.
 * @param lod holds the animation LOD (Module 3) settings.
 */
@Serializable
data class Config(
    var enabled: Boolean = true,
    var cache: CacheConfig = CacheConfig(),
    var parts: PartsConfig = PartsConfig(),
    var lod: LodConfig = LodConfig()
) {
    fun deepCopy() = copy(cache = cache.deepCopy(), parts = parts.deepCopy(), lod = lod.deepCopy())
}

/**
 * Module 1: block entities and static entities drawn from GPU buffers until they change.
 *
 * @param enabled switches the module.
 * @param memoryBudgetMb is the GPU memory the cache may use; the farthest objects are evicted first above it.
 * @param types are the per-type switches (PLAN.md M5, `CachePolicy`).
 * @param prototype makes the M3 chest prototype available (until M5 replaces it); it then starts active and the
 * A/B key toggles it.
 */
@Serializable
data class CacheConfig(
    var enabled: Boolean = true,
    var memoryBudgetMb: Int = DEFAULT_MEMORY_BUDGET_MB,
    var types: CacheTypes = CacheTypes(),
    var prototype: Boolean = false
) {
    fun deepCopy() = copy(types = types.copy())

    companion object {
        const val DEFAULT_MEMORY_BUDGET_MB = 128
        const val MIN_MEMORY_BUDGET_MB = 16
        const val MAX_MEMORY_BUDGET_MB = 1024
    }
}

/**
 * Module 1 per-type switches; a type that is off always takes the vanilla path.
 *
 * @param chests covers chests, trapped chests, ender chests and shulker boxes.
 * @param signs covers signs and hanging signs, including their text.
 * @param skulls covers skulls and heads.
 * @param shelves covers shelves and the items on them.
 * @param itemFrames covers item frames and glow item frames.
 * @param displayEntities covers item and block display entities.
 */
@Serializable
data class CacheTypes(
    var chests: Boolean = true,
    var signs: Boolean = true,
    var beds: Boolean = true,
    var skulls: Boolean = true,
    var decoratedPots: Boolean = true,
    var shelves: Boolean = true,
    var itemFrames: Boolean = true,
    var armorStands: Boolean = true,
    var displayEntities: Boolean = true
) {
    /** Every switch with its key (`mobium.settings.type.<key>`), in display order; the settings screens use it. */
    fun switches(): List<Pair<String, KMutableProperty0<Boolean>>> = listOf(
        "chests" to ::chests,
        "signs" to ::signs,
        "beds" to ::beds,
        "skulls" to ::skulls,
        "decorated_pots" to ::decoratedPots,
        "shelves" to ::shelves,
        "item_frames" to ::itemFrames,
        "armor_stands" to ::armorStands,
        "display_entities" to ::displayEntities
    )
}

/**
 * Module 2: animated models keep CPU posing, the GPU expands one record per part into vertices.
 *
 * @param enabled switches the module; it only runs where compute shaders exist.
 * @param excludedEntities are entity type ids (e.g. `minecraft:zombie`) that always take the vanilla path.
 */
@Serializable
data class PartsConfig(
    var enabled: Boolean = true,
    var excludedEntities: MutableList<String> = mutableListOf()
) {
    fun deepCopy() = copy(excludedEntities = excludedEntities.toMutableList())
}

/**
 * Module 3: far or tiny mobs reuse last frame's part records.
 *
 * @param preset is off by default; any other preset switches the module on.
 * @param excludedEntities are entity type ids that are always posed every frame.
 */
@Serializable
data class LodConfig(
    var preset: LodPreset = LodPreset.OFF,
    var excludedEntities: MutableList<String> = mutableListOf()
) {
    fun deepCopy() = copy(excludedEntities = excludedEntities.toMutableList())
}

/** How aggressively Module 3 skips posing. */
@Serializable
enum class LodPreset {
    OFF,
    SUBTLE,
    AGGRESSIVE
}
