package dev.nyon.mobium.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

var config: Config = Config()
    internal set

const val CONFIG_VERSION = 2

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
        else -> null
    }
}.getOrNull()

/** Version 1 (M0–M2) only had the global switch. */
private fun migrateVersion1(jsonTree: JsonElement): Config =
    Config(enabled = jsonTree.jsonObject["enabled"]?.jsonPrimitive?.booleanOrNull ?: true)
