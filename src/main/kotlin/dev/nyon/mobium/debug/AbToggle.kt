package dev.nyon.mobium.debug

import dev.nyon.mobium.core.ModuleStates
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * The A/B key: switches every Mobium module to the vanilla path and back without touching the saved settings, so
 * the same spot can be compared with and without Mobium. Resets when the game restarts.
 */
object AbToggle {
    var mobiumOff = false
        private set

    /** Flips the state and returns the action bar message, worded like the settings' switch: "Mobium: ON". */
    fun toggle(): Component {
        mobiumOff = !mobiumOff
        ModuleStates.refresh(if (mobiumOff) "A/B key: vanilla" else "A/B key: Mobium")
        return CommonComponents.optionStatus(Component.translatable("mobium.settings.enabled"), !mobiumOff)
    }

    /** Sets the state as the key would, for `/mobium benchmark`, which alternates it and puts it back afterwards. */
    fun set(off: Boolean, cause: String) {
        if (off == mobiumOff) return
        mobiumOff = off
        ModuleStates.refresh(cause)
    }
}
