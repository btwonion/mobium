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

const val CONFIG_VERSION = 3

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
        1 -> migrateVersion1(jsonTree)
        2 -> migrateVersion2(jsonTree)
        else -> null
    }
}.getOrNull()

/** Version 1 (M0–M2) only had the global switch. */
private fun migrateVersion1(jsonTree: JsonElement): Config =
    Config(enabled = jsonTree.jsonObject["enabled"]?.jsonPrimitive?.booleanOrNull ?: true)

/**
 * Version 2 (M3) had the global switch and `cache.prototype`. Version 3 only adds keys, so a valid version 2 file
 * decodes as it is; this branch keeps the two settings when the rest of a version 2 file does not decode.
 */
private fun migrateVersion2(jsonTree: JsonElement): Config {
    val root = jsonTree.jsonObject
    val cache = root["cache"] as? JsonObject
    return Config(
        enabled = root["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
        cache = CacheConfig(prototype = cache?.get("prototype")?.jsonPrimitive?.booleanOrNull ?: false)
    )
}
