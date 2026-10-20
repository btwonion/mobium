package dev.nyon.mobium.test

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState
import net.minecraft.resources.Identifier
import net.minecraft.world.scores.ScoreHolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.walk

/**
 * Not a test: creates the benchmark world for `docs/benchmarks.md` when `./gradlew :26.3-fabric:runBenchWorld` sets
 * [OUTPUT_PROPERTY] — classic superflat, seed "mobium", creative, commands on, the mobium-bench datapack enabled and the
 * empty `control` scene built — and copies the saved world to that directory. Does nothing in normal test runs.
 */
@Suppress("unused")
class BenchWorldGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        val output = System.getProperty(OUTPUT_PROPERTY)?.let(Path::of) ?: return
        val pack = Path.of(checkNotNull(System.getProperty("mobium.benchmarkPack")))

        val save = context.worldBuilder().adjustSettings {
            it.setName(WORLD_NAME)
            it.setSeed("mobium")
            it.setGenerateStructures(true)
            it.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)
            it.setAllowCommands(true)
        }.create().use { singleplayer ->
            val datapack = singleplayer.worldSave.saveDirectory.resolve("datapacks").resolve("mobium-bench")
            copyTree(pack, datapack)
            singleplayer.server.runCommand("reload")
            singleplayer.server.waitFor { it.functions.get(Identifier.fromNamespaceAndPath("mobium-bench", "control")).isPresent }
            singleplayer.server.runCommand("execute as @a at @s run function mobium-bench:control")
            singleplayer.server.waitFor({ server ->
                val objective = server.scoreboard.getObjective("mobium_bench") ?: return@waitFor false
                server.scoreboard.getPlayerScoreInfo(ScoreHolder.forNameOnly("#ready"), objective)?.value() == 1
            }, 20 * 120)
            singleplayer.worldSave.saveDirectory
        }

        val target = output.resolve(WORLD_NAME)
        if (Files.exists(target)) target.toFile().deleteRecursively()
        copyTree(save, target)
    }

    private fun copyTree(from: Path, to: Path) {
        from.walk().forEach { source ->
            val destination = to.resolve(from.relativize(source).toString())
            if (source.isDirectory()) destination.createDirectories() else {
                destination.parent.createDirectories()
                source.copyTo(destination, overwrite = true)
            }
        }
    }

    companion object {
        const val OUTPUT_PROPERTY = "mobium.benchWorld"
        const val WORLD_NAME = "Mobium Bench"

        /** True in `runBenchWorld`, where the real gametests skip themselves. */
        fun creatingBenchWorld() = System.getProperty(OUTPUT_PROPERTY) != null
    }
}
