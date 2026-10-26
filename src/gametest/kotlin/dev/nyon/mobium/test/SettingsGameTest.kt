package dev.nyon.mobium.test

import dev.nyon.mobium.config.applyConfig
import dev.nyon.mobium.config.config
import dev.nyon.mobium.config.screen.MobiumSettingsScreen
import dev.nyon.mobium.core.Fallback
import dev.nyon.mobium.core.Module
import dev.nyon.mobium.core.ModuleStates
import dev.nyon.mobium.core.OffReason
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.client.gui.screens.TitleScreen

/**
 * M4: the settings screen opens with every module's state, a saved change re-resolves the modules at once, and a
 * failure switches its module to vanilla with one toast. Screenshots: `settings-screen`, `fallback-toast`.
 */
@Suppress("unused")
class SettingsGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        if (BenchWorldGameTest.creatingBenchWorld()) return
        context.waitForScreen(TitleScreen::class.java)

        context.runOnClient<RuntimeException> { it.gui.setScreen(MobiumSettingsScreen(checkNotNull(it.gui.screen()))) }
        context.waitTicks(SCREEN_TICKS)
        context.takeScreenshot("settings-screen")
        // The rest of the list: the three module sections.
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

        // Animation LOD does not exist yet, so failing it leaves the other gametests alone (a failure lasts until
        // the game restarts, and the gametests share one game).
        context.runOnClient<RuntimeException> {
            Fallback.moduleFailed(Module.ANIMATION_LOD, IllegalStateException("Forced by SettingsGameTest"))
            Fallback.moduleFailed(Module.ANIMATION_LOD, IllegalStateException("Second failure, no second toast"))
            val status = ModuleStates.status(Module.ANIMATION_LOD)
            check(status.reason == OffReason.FAILED && status.detail == "Forced by SettingsGameTest") { status.describe() }
            checkNotNull(it.gui.toastManager().getToast(SystemToast::class.java, Fallback.TOAST_ID)) { "No failure toast" }
            it.gui.setScreen(MobiumSettingsScreen(TitleScreen()))
        }
        context.waitTicks(TOAST_TICKS)
        context.takeScreenshot("fallback-toast")
        context.runOnClient<RuntimeException> { checkNotNull(it.gui.screen()).onClose() }
        context.waitForScreen(TitleScreen::class.java)
    }

    private companion object {
        const val SCREEN_TICKS = 5
        const val SCROLL_PAGES = 3
        const val SCROLL_PER_PAGE = 6.0
        // Somewhere over the option list of an 854×480 window.
        const val LIST_X = 427.0
        const val LIST_Y = 240.0
        // Long enough for the toast to slide in completely.
        const val TOAST_TICKS = 20
    }
}
