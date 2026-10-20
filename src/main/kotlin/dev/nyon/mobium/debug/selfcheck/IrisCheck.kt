package dev.nyon.mobium.debug.selfcheck

import net.irisshaders.iris.Iris
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Switches Iris shader packs for the dev self-check, through Iris's internal config (Iris 1.11.7: `Iris.getIrisConfig`,
 * `Iris.reload`; the pack options live in `shaderpacks/<pack>.txt`). Only call after checking that Iris is loaded.
 */
internal object IrisCheck {
    /** The first shader pack zip in the game's `shaderpacks` folder, or null. */
    fun firstPack(): String? {
        val directory = Iris.getShaderpacksDirectory()
        if (!Files.isDirectory(directory)) return null
        return Files.list(directory).use { files ->
            files.map { it.fileName.toString() }.filter { it.endsWith(".zip") }.sorted().findFirst().orElse(null)
        }
    }

    /**
     * Selects [pack] with the given pack [options] and reloads Iris; returns the action that puts the previous pack,
     * its switch and the pack's options file back and reloads again.
     */
    fun select(pack: String, options: Map<String, String>): () -> Unit {
        val config = Iris.getIrisConfig()
        val previousPack = config.shaderPackName.orElse(null)
        val previouslyEnabled = config.areShadersEnabled()
        val optionsFile = Iris.getShaderpacksDirectory().resolve("$pack.txt")
        val backup = optionsFile.resolveSibling("$pack.txt.mobium-backup")
        val hadOptions = Files.exists(optionsFile)
        if (hadOptions) Files.copy(optionsFile, backup, StandardCopyOption.REPLACE_EXISTING)
        Files.write(optionsFile, options.map { (key, value) -> "$key=$value" })

        config.setShaderPackName(pack)
        config.setShadersEnabled(true)
        config.save()
        Iris.reload()
        return {
            config.setShaderPackName(previousPack)
            config.setShadersEnabled(previouslyEnabled)
            config.save()
            restoreOptions(optionsFile, backup, hadOptions)
            Iris.reload()
        }
    }

    private fun restoreOptions(optionsFile: Path, backup: Path, hadOptions: Boolean) {
        if (hadOptions) {
            Files.move(backup, optionsFile, StandardCopyOption.REPLACE_EXISTING)
        } else {
            Files.deleteIfExists(optionsFile)
        }
    }
}
