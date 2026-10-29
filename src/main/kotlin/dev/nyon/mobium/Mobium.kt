package dev.nyon.mobium

import dev.nyon.konfig.config.config
import dev.nyon.konfig.config.loadConfig
import dev.nyon.mobium.bench.BenchAutorun
import dev.nyon.mobium.config.CONFIG_VERSION
import dev.nyon.mobium.config.Config
import dev.nyon.mobium.config.config
import dev.nyon.mobium.config.migrate
import dev.nyon.mobium.core.Capabilities
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.ModuleStates
import dev.nyon.mobium.debug.VulkanValidation
import dev.nyon.mobium.debug.selfcheck.SelfCheck
import dev.nyon.mobium.parts.ExpandPass
import net.minecraft.client.Minecraft
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.Properties

object Mobium {
    const val MOD_ID = "mobium"

    @JvmField
    val LOGGER: Logger = LoggerFactory.getLogger("Mobium")

    /** The commit this jar was built from (`git describe`, written by the build), or "unknown". */
    val BUILD: String by lazy {
        val properties = Properties()
        Mobium::class.java.getResourceAsStream("/mobium-build.properties")?.use(properties::load)
        properties.getProperty("commit") ?: "unknown"
    }

    /** Loads the config and detects the installed compat mods. Runs once, from the loader entrypoint. */
    fun bootstrap(configPath: Path) {
        config(configPath, CONFIG_VERSION, Config()) { json, jsonTree, version -> migrate(json, jsonTree, version) }
        config = loadConfig<Config>()

        LOGGER.info("Build: {}", BUILD)
        Compat.detect()
        LOGGER.info("Compat mods: {}", Compat.describe())
    }

    /** Runs once on the render thread after the GPU device exists (before the first client tick). */
    fun onClientStarted() {
        val capabilities = Capabilities.detect()
        LOGGER.info("Graphics: {}", capabilities.describe())
        VulkanValidation.installIfRequested()
        ExpandPass.configure(capabilities)
        if (capabilities.compute) LOGGER.info("Part transform: expand pass as {}", ExpandPass.describeChoice())
        ModuleStates.onClientStarted()
        SelfCheck.startIfRequested(Minecraft.getInstance())
        BenchAutorun.startIfRequested(Minecraft.getInstance())
    }
}
