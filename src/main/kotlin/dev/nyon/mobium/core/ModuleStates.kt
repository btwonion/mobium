package dev.nyon.mobium.core

import dev.nyon.mobium.Mobium
import dev.nyon.mobium.bench.BenchSuite
import dev.nyon.mobium.cache.StaticCache
import dev.nyon.mobium.config.config
import dev.nyon.mobium.debug.AbToggle
import dev.nyon.mobium.gpu.vk.VulkanSupport
import dev.nyon.mobium.parts.PartTransform

/**
 * The live module states: [ModuleRules] applied to the current config and environment. Re-resolved by [refresh]
 * whenever an input changes (settings saved, A/B key, a failure, a shader pack switched on or off); every module
 * that changes state is logged. [isActive] is the per-frame check and does not allocate. Render thread only.
 */
object ModuleStates {
    private const val SHADER_PACK_POLL_TICKS = 20

    private val active = BooleanArray(Module.entries.size)
    private var statuses: Map<Module, ModuleStatus> = emptyMap()
    private var shaderPack = false
    private var ticks = 0

    @JvmStatic
    fun isActive(module: Module): Boolean = active[module.ordinal]

    fun status(module: Module): ModuleStatus = statuses[module] ?: ModuleStatus(module, OffReason.BACKEND)

    fun all(): List<ModuleStatus> = Module.entries.map(::status)

    /** Re-resolves every module; [cause] goes into the log line of each module that changes state. */
    fun refresh(cause: String) {
        shaderPack = shaderPackInUse()
        val environment = ModuleEnvironment(
            backend = Capabilities.current?.backend,
            compute = Capabilities.current?.compute ?: false,
            shaderPack = shaderPack,
            abOff = AbToggle.mobiumOff,
            overlaps = Compat.overlaps(),
            untested = Compat.untested(),
            vulkanParts = VulkanSupport.allowed,
            failures = Fallback.failures
        )
        val next = ModuleRules.resolve(config, environment)
        for (module in Module.entries) {
            val before = statuses[module]
            val after = next.getValue(module)
            if (before == null || before != after) {
                Mobium.LOGGER.info(
                    "Mobium {}: {}{} ({})",
                    module.key, if (before == null) "" else "${before.describe()} -> ", after.describe(), cause
                )
            }
            active[module.ordinal] = after.active
        }
        statuses = next
        apply()
    }

    /** Polls what has no event of its own; called once per client tick. */
    fun tick() {
        if (statuses.isEmpty() || ++ticks < SHADER_PACK_POLL_TICKS) return
        ticks = 0
        if (shaderPackInUse() != shaderPack) refresh(if (shaderPack) "shader pack off" else "shader pack on")
    }

    /**
     * Hands the states to the module code. While a benchmark plan runs, it decides the modules itself, except
     * `/mobium benchmark`, which switches Mobium off and on through the A/B state.
     */
    private fun apply() {
        PartTransform.configure(config.parts.excludedEntities)
        if (BenchSuite.decidesModules) return
        StaticCache.setActive(isActive(Module.STATIC_CACHE) && StaticCache.anyTypeAllowed())
        PartTransform.setActive(isActive(Module.PART_TRANSFORM))
    }

    private fun shaderPackInUse(): Boolean = Compat.isLoaded(CompatMod.IRIS) && IrisCompat.isShaderPackInUse()

    /** Called once the GPU device exists. */
    fun onClientStarted() = refresh("startup")
}
