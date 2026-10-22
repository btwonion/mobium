package dev.nyon.mobium.config

import dev.nyon.konfig.config.saveConfig
import dev.nyon.mobium.core.ModuleStates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

var config: Config = Config()
    internal set

const val CONFIG_VERSION = 4

/**
 * Replaces the config with [value], writes it to disk and re-resolves the module states, which logs every module
 * that changes state. Render thread only.
 */
fun applyConfig(value: Config) {
    config = value
    saveConfig(value)
    ModuleStates.refresh("settings changed")
}

/**
 * Converts older formats when Konfig requests a migration. Konfig only asks after the current format failed to
 * decode, so unknown keys must keep failing: that is what routes old configs here instead of to defaults.
 *
 * @param json is the serializer configured by Konfig.
 * @param jsonTree is the config content supplied by Konfig.
 * @param version is the stored config version, or null for an unversioned file.
 * @return the migrated config, or null if Konfig should apply its default config.
 */
@Suppress("UNUSED_PARAMETER")
internal fun migrate(json: Json, jsonTree: JsonElement, version: Int?): Config? = runCatching {
    when (version) {
        1, 2 -> migrateVersion1(jsonTree)
        3 -> migrateVersion3(json, jsonTree)
        else -> null
    }
}.getOrNull()

/**
 * Version 1 (M0–M2) only had the global switch; version 2 (M3) added `cache.prototype`, which the full static cache
 * (M5) no longer needs. Both keep the global switch only.
 */
private fun migrateVersion1(jsonTree: JsonElement): Config =
    Config(enabled = jsonTree.jsonObject["enabled"]?.jsonPrimitive?.booleanOrNull ?: true)

/**
 * Version 3 (M4) had `cache.prototype` (the opt-in for the M3 chest prototype) and the per-type switch
 * `cache.types.beds` (beds have no block entity in 26.3). Both are dropped, everything else is kept.
 */
private fun migrateVersion3(json: Json, jsonTree: JsonElement): Config {
    val root = jsonTree.jsonObject
    val cache = root["cache"] as? JsonObject ?: return json.decodeFromJsonElement(Config.serializer(), root)
    val types = cache["types"] as? JsonObject
    val newTypes = if (types == null) emptyMap() else mapOf("types" to JsonObject(types - "beds"))
    val newCache = JsonObject(cache - "prototype" + newTypes)
    return json.decodeFromJsonElement(Config.serializer(), JsonObject(root + ("cache" to newCache)))
}
