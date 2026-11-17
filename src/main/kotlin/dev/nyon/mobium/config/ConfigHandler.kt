package dev.nyon.mobium.config

import dev.nyon.konfig.config.saveConfig
import dev.nyon.mobium.core.ModuleStates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

var config: Config = Config()
    internal set

const val CONFIG_VERSION = 1

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
 * Called by Konfig after the file failed to decode as the current format; Konfig decodes whatever version the file
 * names, so unknown keys must keep failing: that is what routes a file of another format here instead of silently
 * dropping its settings. No other format is converted: such a file gets the default config. A format change bumps
 * [CONFIG_VERSION] and adds a branch here for the version it replaces.
 *
 * @param json is the serializer configured by Konfig.
 * @param jsonTree is the config content supplied by Konfig.
 * @param version is the stored config version, or null for an unversioned file.
 * @return the migrated config, or null if Konfig should apply its default config.
 */
@Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
internal fun migrate(json: Json, jsonTree: JsonElement, version: Int?): Config? = null
