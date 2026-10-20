package dev.nyon.mobium.core

import dev.nyon.mobium.Mobium
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component

/**
 * Bookkeeping of the fallback ladder (PLAN.md §6) without Minecraft classes, unit-tested:
 * - a module failure switches the module off for the session; only its first failure is reported;
 * - a type that fails [TYPE_FAILURE_LIMIT] times takes the vanilla path for the session.
 * Per-submit fallbacks (an unknown vertex consumer, an unsupported object) are not failures; the caller just takes
 * the vanilla path.
 */
class FailureLedger {
    private val moduleFailures = LinkedHashMap<Module, String>()
    private val typeFailures = HashMap<Pair<Module, String>, Int>()
    private val disabledTypes = LinkedHashSet<Pair<Module, String>>()

    val failures: Map<Module, String>
        get() = moduleFailures

    /** Records a module failure; returns true if it is the module's first, which is the one to log and show. */
    fun moduleFailed(module: Module, message: String): Boolean {
        if (module in moduleFailures) return false
        moduleFailures[module] = message
        return true
    }

    /** Records a failure of one type; returns true when this failure switches the type off. */
    fun typeFailed(module: Module, type: String): Boolean {
        val key = module to type
        if (key in disabledTypes) return false
        val count = (typeFailures[key] ?: 0) + 1
        typeFailures[key] = count
        if (count < TYPE_FAILURE_LIMIT) return false
        disabledTypes += key
        return true
    }

    fun isTypeDisabled(module: Module, type: String): Boolean = (module to type) in disabledTypes

    /** Types switched off by failures, as `module/type`. */
    fun disabledTypes(): List<String> = disabledTypes.map { "${it.first.key}/${it.second}" }

    fun clear() {
        moduleFailures.clear()
        typeFailures.clear()
        disabledTypes.clear()
    }

    companion object {
        const val TYPE_FAILURE_LIMIT = 3
    }
}

/**
 * Where modules report failures. A module catches its own exceptions at its entry points, falls back to vanilla for
 * the current object, and calls [moduleFailed] (or [typeFailed] for errors that only concern one type). The first
 * failure of a module is logged with its stack trace and shown as one toast; the module then stays on vanilla until
 * the game restarts. Render thread only.
 */
object Fallback {
    private val ledger = FailureLedger()

    /** Token of the failure toast (tests look it up with `ToastManager.getToast`). */
    @JvmField
    val TOAST_ID = SystemToast.SystemToastId()

    val failures: Map<Module, String>
        get() = ledger.failures

    fun moduleFailed(module: Module, error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        if (!ledger.moduleFailed(module, message)) return
        Mobium.LOGGER.error("Mobium module {} failed and is off until restart; vanilla draws instead", module.key, error)
        ModuleStates.refresh("${module.key} failed")
        SystemToast.add(
            Minecraft.getInstance().gui.toastManager(),
            TOAST_ID,
            Component.translatable("mobium.toast.failed.title").withStyle(ChatFormatting.RED),
            Component.translatable("mobium.toast.failed.message", Component.translatable(module.translationKey))
        )
    }

    fun typeFailed(module: Module, type: String, error: Throwable) {
        if (ledger.typeFailed(module, type)) {
            Mobium.LOGGER.warn(
                "Mobium {}: {} failed {} times and takes the vanilla path until restart",
                module.key, type, FailureLedger.TYPE_FAILURE_LIMIT, error
            )
        }
    }

    fun isTypeDisabled(module: Module, type: String): Boolean = ledger.isTypeDisabled(module, type)

    fun disabledTypes(): List<String> = ledger.disabledTypes()
}
