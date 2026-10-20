package dev.nyon.mobium.debug

import dev.nyon.mobium.MobiumKeys
import dev.nyon.mobium.core.ModuleStates
import net.minecraft.network.chat.Component

/**
 * The A/B key: switches every Mobium module to the vanilla path and back without touching the saved settings, so
 * the same spot can be compared with and without Mobium. Resets when the game restarts.
 */
object AbToggle {
    var mobiumOff = false
        private set

    /**
     * Flips the state and returns the action bar message, which names the bound key: bindings are physical key
     * positions, so the default (right of P) reads `]` on a US keyboard and `+` on a German one.
     */
    fun toggle(): Component {
        mobiumOff = !mobiumOff
        ModuleStates.refresh(if (mobiumOff) "A/B key: vanilla" else "A/B key: Mobium")
        return Component.translatable(if (mobiumOff) "mobium.ab.vanilla" else "mobium.ab.mobium", keyName())
    }

    /** The A/B key as the keyboard layout names it. */
    fun keyName(): Component = MobiumKeys.AB_TOGGLE.translatedKeyMessage
}
