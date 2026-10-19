package dev.nyon.mobium

import com.mojang.blaze3d.platform.InputConstants
import dev.nyon.mobium.cache.ChestPrototype
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

/** Mobium's key bindings; registered by the loader entrypoints and polled once per client tick. */
object MobiumKeys {
    @JvmField
    val CATEGORY = KeyMapping.Category(Identifier.fromNamespaceAndPath(Mobium.MOD_ID, "main"))

    /** Temporary (M3): toggles the chest cache prototype live, for A/B comparisons in the same spot. */
    @JvmField
    val TOGGLE_CHEST_PROTOTYPE = KeyMapping("key.mobium.toggle_chest_prototype", InputConstants.KEY_RBRACKET, CATEGORY)

    val ALL: List<KeyMapping> = listOf(TOGGLE_CHEST_PROTOTYPE)

    @JvmStatic
    fun tick(minecraft: Minecraft) {
        while (TOGGLE_CHEST_PROTOTYPE.consumeClick()) {
            minecraft.gui.hud.setOverlayMessage(ChestPrototype.toggle(), false)
        }
    }
}
