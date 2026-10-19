package dev.nyon.mobium.config

import kotlinx.serialization.Serializable

/**
 * The persisted settings. M0 only carries the global switch; the per-module and per-type switches, budgets and the
 * LOD preset arrive with the settings screen (PLAN.md M4), each with a [CONFIG_VERSION] bump and a migration branch.
 *
 * @param enabled is the global switch; when false every module uses the vanilla path.
 */
@Serializable
data class Config(
    var enabled: Boolean = true
)
