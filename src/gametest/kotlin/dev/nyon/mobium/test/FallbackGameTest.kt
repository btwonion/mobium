package dev.nyon.mobium.test

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
 * A failure switches its module to vanilla with one toast, however often it is reported. Screenshot:
 * `fallback-toast`. Runs last: a failure lasts until the game restarts, and the gametests share one game.
 */
@Suppress("unused")
class FallbackGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        if (BenchWorldGameTest.creatingBenchWorld()) return
        context.waitForScreen(TitleScreen::class.java)

        context.runOnClient<RuntimeException> {
            Fallback.moduleFailed(Module.STATIC_CACHE, IllegalStateException("Forced by FallbackGameTest"))
            Fallback.moduleFailed(Module.STATIC_CACHE, IllegalStateException("Second failure, no second toast"))
            val status = ModuleStates.status(Module.STATIC_CACHE)
            check(status.reason == OffReason.FAILED && status.detail == "Forced by FallbackGameTest") { status.describe() }
            checkNotNull(it.gui.toastManager().getToast(SystemToast::class.java, Fallback.TOAST_ID)) { "No failure toast" }
            it.gui.setScreen(MobiumSettingsScreen(TitleScreen()))
        }
        context.waitTicks(TOAST_TICKS)
        context.takeScreenshot("fallback-toast")
        context.runOnClient<RuntimeException> { checkNotNull(it.gui.screen()).onClose() }
        context.waitForScreen(TitleScreen::class.java)
    }

    private companion object {
        // Long enough for the toast to slide in completely.
        const val TOAST_TICKS = 20
    }
}
