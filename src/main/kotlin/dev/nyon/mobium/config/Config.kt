package dev.nyon.mobium.config

import kotlinx.serialization.Serializable

/**
 * The persisted settings. The per-module and per-type switches, budgets and the LOD preset arrive with the settings
 * screen (PLAN.md M4), each with a [CONFIG_VERSION] bump and a migration branch.
 *
 * @param enabled is the global switch; when false every module uses the vanilla path.
 * @param cache holds the static cache (Module 1) settings.
 */
@Serializable
data class Config(
    var enabled: Boolean = true,
    var cache: CacheConfig = CacheConfig()
)

/**
 * @param prototype makes the M3 chest prototype available; it then starts active and its key binding toggles it.
 */
@Serializable
data class CacheConfig(
    var prototype: Boolean = false
)
