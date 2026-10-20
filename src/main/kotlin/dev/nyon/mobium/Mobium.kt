package dev.nyon.mobium

import dev.nyon.konfig.config.config
import dev.nyon.konfig.config.loadConfig
import dev.nyon.mobium.config.CONFIG_VERSION
import dev.nyon.mobium.config.Config
import dev.nyon.mobium.config.config
import dev.nyon.mobium.config.migrate
import dev.nyon.mobium.core.Capabilities
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.ModuleStates
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path

object Mobium {
    const val MOD_ID = "mobium"

    @JvmField
    val LOGGER: Logger = LoggerFactory.getLogger("Mobium")

    /** Loads the config and detects the installed compat mods. Runs once, from the loader entrypoint. */
    fun bootstrap(configPath: Path) {
        config(configPath, CONFIG_VERSION, Config()) { json, jsonTree, version -> migrate(json, jsonTree, version) }
        config = loadConfig<Config>()

        Compat.detect()
        LOGGER.info("Compat mods: {}", Compat.describe())
    }

    /** Runs once on the render thread after the GPU device exists (before the first client tick). */
    fun onClientStarted() {
        val capabilities = Capabilities.detect()
        LOGGER.info("Graphics: {}", capabilities.describe())
        ModuleStates.onClientStarted()
    }
}
