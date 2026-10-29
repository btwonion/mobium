package dev.nyon.mobium.test

import dev.nyon.mobium.core.Capabilities
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.TitleScreen

/**
 * Smoke test: the client reaches the title screen with Mobium loaded and its startup detection done, on the graphics
 * API the launch asked for.
 */
@Suppress("unused")
class StartupGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        if (BenchWorldGameTest.creatingBenchWorld()) return
        check(FabricLoader.getInstance().isModLoaded("mobium")) { "Mobium is not loaded" }

        context.waitForScreen(TitleScreen::class.java)
        context.waitTicks(TITLE_FADE_IN_TICKS)
        context.runOnClient<RuntimeException> {
            val capabilities = checkNotNull(Capabilities.current) { "Capabilities were not detected on client start" }
            // -Pmobium.graphicsBackend: Minecraft falls back to the other API when the chosen one fails to start.
            val wanted = System.getProperty("mobium.graphicsBackend")
            if (wanted != null && wanted != "default") {
                check(capabilities.backendName.equals(wanted, ignoreCase = true)) { "Asked for $wanted, running on ${capabilities.describe()}" }
            }
        }
        context.takeScreenshot("startup-title-screen")
    }

    private companion object {
        // The title screen fades its widgets in over the first second; wait so the screenshot shows the final state.
        const val TITLE_FADE_IN_TICKS = 40
    }
}
