package dev.nyon.mobium.test

import dev.nyon.mobium.config.applyConfig
import dev.nyon.mobium.config.config
import dev.nyon.mobium.config.screen.MobiumSettingsScreen
import dev.nyon.mobium.core.Module
import dev.nyon.mobium.core.ModuleStates
import dev.nyon.mobium.core.OffReason
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.gui.screens.TitleScreen

/**
 * The settings screen opens and a saved change re-resolves the modules at once. Screenshots: `settings-screen`
 * (and one per scrolled page). A failure's toast is [FallbackGameTest].
 */
@Suppress("unused")
class SettingsGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        if (BenchWorldGameTest.creatingBenchWorld()) return
        context.waitForScreen(TitleScreen::class.java)

        context.runOnClient<RuntimeException> { it.gui.setScreen(MobiumSettingsScreen(checkNotNull(it.gui.screen()))) }
        context.waitTicks(SCREEN_TICKS)
        context.takeScreenshot("settings-screen")
        // The rest of the list: the two module sections.
        context.input.setCursorPos(LIST_X, LIST_Y)
        for (page in 1..SCROLL_PAGES) {
            context.input.scroll(-SCROLL_PER_PAGE)
            context.waitTicks(SCREEN_TICKS)
            context.takeScreenshot("settings-screen-$page")
        }

        // The same call the Done button and Sodium's Apply make.
        context.runOnClient<RuntimeException> {
            val saved = config
            check(ModuleStates.isActive(Module.STATIC_CACHE)) {
                "Unexpected start state: ${ModuleStates.status(Module.STATIC_CACHE).describe()}"
            }
            applyConfig(saved.deepCopy().also { changed -> changed.cache.enabled = false })
            check(ModuleStates.status(Module.STATIC_CACHE).reason == OffReason.CONFIG)
            applyConfig(saved.deepCopy().also { changed -> changed.enabled = false })
            check(ModuleStates.status(Module.STATIC_CACHE).reason == OffReason.GLOBAL_SWITCH)
            applyConfig(saved)
            check(ModuleStates.isActive(Module.STATIC_CACHE)) { ModuleStates.status(Module.STATIC_CACHE).describe() }
        }
        context.runOnClient<RuntimeException> { checkNotNull(it.gui.screen()).onClose() }
        context.waitForScreen(TitleScreen::class.java)
    }

    private companion object {
        const val SCREEN_TICKS = 5
        const val SCROLL_PAGES = 2
        const val SCROLL_PER_PAGE = 6.0
        // Somewhere over the option list of an 854×480 window.
        const val LIST_X = 427.0
        const val LIST_Y = 240.0
    }
}
