package dev.nyon.mobium.debug.selfcheck

import dev.vitrail.render.PackChain
import dev.vitrail.render.PackChoice
import dev.vitrail.settings.PackFile
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Switches Vitrail shader packs for the dev self-check, the way its settings screen does (Vitrail 0.13.0-beta,
 * docs/vanilla-notes.md §27): the choice goes into `vitrail/pack.txt`, the pack's options into
 * `shaderpacks/<pack>.txt` (the file Iris uses too), and `PackChoice.reload` reads both again. Only call after
 * checking that Vitrail is loaded ([ShaderPackCheck.vitrail]).
 */
internal object VitrailCheck {
    private fun gameDirectory(): Path = Minecraft.getInstance().gameDirectory.toPath()

    private fun packs(): Path = gameDirectory().resolve("shaderpacks")

    /** The first shader pack zip in the game's `shaderpacks` folder, or null. */
    fun firstPack(): String? {
        val directory = packs()
        if (!Files.isDirectory(directory)) return null
        return Files.list(directory).use { files ->
            files.map { it.fileName.toString() }.filter { it.endsWith(".zip") }.sorted().findFirst().orElse(null)
        }
    }

    /** A pack is loaded and Vitrail draws with it. */
    fun inUse(): Boolean = PackChain.drawingPack()

    /** Why the last load drew nothing, or an empty string. */
    fun error(): String = PackChoice.lastError().orElse("")

    /**
     * Selects [pack] with the given pack [options] and reloads; returns the action that puts the previous choice and
     * the pack's options file back and reloads again.
     */
    fun select(pack: String, options: Map<String, String>): () -> Unit {
        val directory = gameDirectory()
        val choice = PackChoice.packFile(directory)
        val previous = PackFile.read(choice)
        val optionsFile = packs().resolve("$pack.txt")
        val backup = optionsFile.resolveSibling("$pack.txt.mobium-backup")
        val hadOptions = Files.exists(optionsFile)
        if (hadOptions) Files.copy(optionsFile, backup, StandardCopyOption.REPLACE_EXISTING)
        Files.write(optionsFile, options.map { (key, value) -> "$key=$value" })

        PackFile.write(choice, previous.withChoice(pack, true))
        PackChoice.reload(directory)
        return {
            PackFile.write(choice, previous.withChoice(previous.name(), false))
            if (hadOptions) Files.move(backup, optionsFile, StandardCopyOption.REPLACE_EXISTING) else Files.deleteIfExists(optionsFile)
            PackChoice.reload(directory)
        }
    }
}

/**
 * The shader pack loader of the running dev client: Vitrail where it is installed (Vulkan runs with
 * `-Pmobium.vitrail`), else Iris.
 */
internal object ShaderPackCheck {
    val vitrail: Boolean by lazy { dev.nyon.mobium.core.Compat.modVersion("vitrail") != null }

    private val iris: Boolean
        get() = dev.nyon.mobium.core.Compat.isLoaded(dev.nyon.mobium.core.CompatMod.IRIS)

    /** A mod that loads shader packs is installed. */
    val available: Boolean
        get() = vitrail || iris

    val loader: String
        get() = if (vitrail) "Vitrail" else "Iris"

    fun firstPack(): String? = if (vitrail) VitrailCheck.firstPack() else IrisCheck.firstPack()

    fun select(pack: String, options: Map<String, String>): () -> Unit =
        if (vitrail) VitrailCheck.select(pack, options) else IrisCheck.select(pack, options)

    fun inUse(): Boolean = if (vitrail) VitrailCheck.inUse() else dev.nyon.mobium.core.IrisCompat.isShaderPackInUse()
}
