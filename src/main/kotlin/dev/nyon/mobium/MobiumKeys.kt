package dev.nyon.mobium

import com.mojang.blaze3d.platform.InputConstants
import dev.nyon.mobium.core.ModuleStates
import dev.nyon.mobium.debug.AbToggle
import dev.nyon.mobium.debug.Validation
import dev.nyon.mobium.debug.selfcheck.SelfCheck
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

/** Mobium's key bindings; registered by the loader entrypoints and polled once per client tick. */
object MobiumKeys {
    @JvmField
    val CATEGORY = KeyMapping.Category(Identifier.fromNamespaceAndPath(Mobium.MOD_ID, "main"))

    /** Switches all of Mobium to the vanilla path and back, for A/B comparisons in the same spot ([AbToggle]). */
    @JvmField
    val AB_TOGGLE = KeyMapping("key.mobium.ab_toggle", InputConstants.KEY_RBRACKET, CATEGORY)

    val ALL: List<KeyMapping> = listOf(AB_TOGGLE)

    @JvmStatic
    fun tick(minecraft: Minecraft) {
        while (AB_TOGGLE.consumeClick()) {
            minecraft.gui.hud.setOverlayMessage(AbToggle.toggle(), false)
        }
        ModuleStates.tick()
        Validation.tick()
        SelfCheck.tick()
    }
}
