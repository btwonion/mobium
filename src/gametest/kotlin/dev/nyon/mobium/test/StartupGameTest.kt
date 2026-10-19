package dev.nyon.mobium.test

import dev.nyon.mobium.core.Capabilities
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.TitleScreen

/** Smoke test: the client reaches the title screen with Mobium loaded and its startup detection done. */
@Suppress("unused")
class StartupGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        check(FabricLoader.getInstance().isModLoaded("mobium")) { "Mobium is not loaded" }

        context.waitForScreen(TitleScreen::class.java)
        context.waitTicks(TITLE_FADE_IN_TICKS)
        context.runOnClient<RuntimeException> {
            checkNotNull(Capabilities.current) { "Capabilities were not detected on client start" }
        }
        context.takeScreenshot("startup-title-screen")
    }

    private companion object {
        // The title screen fades its widgets in over the first second; wait so the screenshot shows the final state.
        const val TITLE_FADE_IN_TICKS = 40
    }
}
