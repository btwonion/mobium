package dev.nyon.mobium.core

import dev.nyon.mobium.config.Config
import dev.nyon.mobium.config.LodPreset

/** Mobium's three modules (docs/architecture.md). [key] names them in logs, commands and translation keys. */
enum class Module(val key: String) {
    STATIC_CACHE("static_cache"),
    PART_TRANSFORM("part_transform"),
    ANIMATION_LOD("animation_lod");

    val translationKey: String
        get() = "mobium.module.$key"

    companion object {
        fun byKey(key: String): Module? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Why a module takes the vanilla path, in the order [ModuleRules.resolve] checks them: the first one that applies
 * is the one shown.
 */
enum class OffReason(val key: String) {
    /** An exception or GPU error switched the module off for this session ([Fallback]). */
    FAILED("failed"),

    /** The global switch in the settings is off. */
    GLOBAL_SWITCH("global_switch"),

    /** The A/B key switched Mobium off until it is pressed again. */
    AB_TOGGLE("ab_toggle"),

    /** The module's own switch is off. */
    CONFIG("config"),

    /** A mod that does the same job is installed ([Compat.overlaps]). */
    OVERLAP("overlap"),

    /** A mod that hooks the same rendering paths and is not tested with Mobium is installed ([Compat.untested]). */
    UNTESTED("untested"),

    /** The module does not run on the active backend: Module 2 needs OpenGL until its Vulkan dispatch exists (PLAN.md M7). */
    BACKEND("backend"),

    /** The module needs compute shaders (OpenGL 4.3 with double precision in shaders). */
    NO_COMPUTE("no_compute"),

    /** The module's code does not exist yet (Module 3: PLAN.md, *Later*). */
    NOT_BUILT("not_built");

    val translationKey: String
        get() = "mobium.reason.$key"
}

/**
 * The resolved state of one module.
 *
 * @param reason is null exactly when [active].
 * @param detail adds context to the reason (the overlapping mod, the failure message); may be empty.
 */
data class ModuleStatus(val module: Module, val reason: OffReason?, val detail: String = "") {
    val active: Boolean
        get() = reason == null

    fun describe(): String = if (active) "active" else "vanilla (${reason!!.key}${if (detail.isEmpty()) "" else ": $detail"})"
}

/**
 * Everything besides the config that decides whether a module may run.
 *
 * @param backend is null before the GPU device exists.
 * @param shaderPack says whether an Iris shader pack is in use; no rule depends on it (the static cache captures and
 * Module 2 expands in Iris's extended vertex format).
 * @param overlaps names, per module, an installed mod that does the same job.
 * @param untested names, per module, an installed mod that hooks the same paths and is not tested with Mobium.
 * @param vulkanParts lets Module 2 run on Vulkan while its checks there are being built (PLAN.md M7 step 5).
 * @param failures holds, per module, the message of the failure that switched it off.
 */
data class ModuleEnvironment(
    val backend: GraphicsBackend?,
    val compute: Boolean,
    val shaderPack: Boolean,
    val abOff: Boolean,
    val overlaps: Map<Module, String> = emptyMap(),
    val untested: Map<Module, String> = emptyMap(),
    val vulkanParts: Boolean = false,
    val failures: Map<Module, String> = emptyMap()
)

/** Pure rules from config and environment to module states; no Minecraft classes, unit-tested. */
object ModuleRules {
    fun resolve(config: Config, environment: ModuleEnvironment): Map<Module, ModuleStatus> =
        Module.entries.associateWith { resolve(it, config, environment) }

    fun resolve(module: Module, config: Config, environment: ModuleEnvironment): ModuleStatus {
        fun off(reason: OffReason, detail: String = "") = ModuleStatus(module, reason, detail)

        environment.failures[module]?.let { return off(OffReason.FAILED, it) }
        if (!config.enabled) return off(OffReason.GLOBAL_SWITCH)
        if (environment.abOff) return off(OffReason.AB_TOGGLE)
        val switchedOn = when (module) {
            Module.STATIC_CACHE -> config.cache.enabled
            Module.PART_TRANSFORM -> config.parts.enabled
            Module.ANIMATION_LOD -> config.lod.preset != LodPreset.OFF
        }
        if (!switchedOn) return off(OffReason.CONFIG)
        environment.overlaps[module]?.let { return off(OffReason.OVERLAP, it) }
        environment.untested[module]?.let { return off(OffReason.UNTESTED, it) }
        if (!runsOn(module, environment)) return off(OffReason.BACKEND, environment.backend?.name?.lowercase() ?: "")
        return when (module) {
            Module.STATIC_CACHE -> ModuleStatus(module, null)
            Module.PART_TRANSFORM -> if (!environment.compute) off(OffReason.NO_COMPUTE) else ModuleStatus(module, null)
            Module.ANIMATION_LOD -> off(OffReason.NOT_BUILT)
        }
    }

    /**
     * The static cache draws through Renderpearl only and runs on OpenGL and Vulkan (docs/vanilla-notes.md §22);
     * Module 2 runs on OpenGL, and on Vulkan only with [ModuleEnvironment.vulkanParts] until its checks there are done
     * (PLAN.md M7 step 5; whether the GPU can is the `compute` rule). No backend before the device exists.
     */
    private fun runsOn(module: Module, environment: ModuleEnvironment): Boolean = when (module) {
        Module.STATIC_CACHE, Module.ANIMATION_LOD ->
            environment.backend == GraphicsBackend.OPENGL || environment.backend == GraphicsBackend.VULKAN
        Module.PART_TRANSFORM ->
            environment.backend == GraphicsBackend.OPENGL || (environment.backend == GraphicsBackend.VULKAN && environment.vulkanParts)
    }
}
