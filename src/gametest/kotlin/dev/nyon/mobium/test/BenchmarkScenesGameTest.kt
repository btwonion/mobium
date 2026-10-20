package dev.nyon.mobium.test

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import dev.nyon.mobium.bench.BenchSuite
import dev.nyon.mobium.cache.ChestPrototype
import dev.nyon.mobium.config.config
import dev.nyon.mobium.core.Module
import dev.nyon.mobium.core.ModuleStates
import dev.nyon.mobium.debug.DebugLines
import dev.nyon.mobium.debug.Sections
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.gui.components.debug.DebugScreenEntries
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.level.Level
import net.minecraft.world.scores.ScoreHolder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.walk
import kotlin.math.sqrt

/**
 * Builds every benchmark scene from `benchmark/mobium-bench` in the benchmark world (classic superflat, seed "mobium")
 * and saves a baseline screenshot per scene (`scene_<name>`). Also checks that the scene origin is still far from any
 * village, which the datapack relies on.
 */
@Suppress("unused")
class BenchmarkScenesGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        if (BenchWorldGameTest.creatingBenchWorld()) return
        val pack = Path.of(checkNotNull(System.getProperty(PACK_PROPERTY)) { "$PACK_PROPERTY is not set" })
        check(Files.isRegularFile(pack.resolve("pack.mcmeta"))) { "No datapack at $pack" }

        context.worldBuilder().adjustSettings {
            it.setSeed(SEED)
            it.setGenerateStructures(true)
        }.create().use { singleplayer ->
            installPack(singleplayer, pack)
            checkOriginIsClear(singleplayer)
            context.runOnClient<RuntimeException> {
                if (!it.gui.hud.isHidden) it.gui.hud.toggle()
                // As in the protocol: benchmarks must not stop at vanilla's default 120 FPS cap.
                it.options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF)
                it.options.enableVsync().set(false)
            }

            for (scene in SCENES) {
                singleplayer.server.runCommand("execute as @a at @s run function $NAMESPACE:$scene")
                singleplayer.server.waitFor({ server -> isReady(server) }, SCENE_TIMEOUT_TICKS)
                singleplayer.connection.waitForChunksRender()
                if (scene == "map_wall") context.waitFor({ mapsSynced(it) }, MAP_SYNC_TIMEOUT_TICKS)
                context.waitTicks(SETTLE_TICKS)
                val vanilla = context.takeScreenshot("scene_$scene")
                if (scene == "storage_room") checkChestPrototype(context, vanilla)
            }

            checkBenchCommand(context)
            checkDebugEntry(context)
            checkSuiteCommand(context)
        }
    }

    /** Shows the `mobium:timings` F3 entry on the live mob scene and checks that it is drawn. */
    private fun checkDebugEntry(context: ClientGameTestContext) {
        context.runOnClient<RuntimeException> {
            checkNotNull(DebugScreenEntries.getEntry(DebugLines.ID)) { "mobium:timings is not registered" }
            if (it.gui.hud.isHidden) it.gui.hud.toggle()
            it.debugEntries.setStatus(DebugLines.ID, DebugScreenEntryStatus.ALWAYS_ON)
        }
        context.waitTicks(SETTLE_TICKS)
        context.runOnClient<RuntimeException> {
            check(Sections.frame - Sections.debugShownFrame <= 2) { "mobium:timings was not displayed" }
        }
        context.takeScreenshot("debug_timings")
        context.runOnClient<RuntimeException> { it.debugEntries.setStatus(DebugLines.ID, DebugScreenEntryStatus.NEVER) }
    }

    /**
     * M3: switches the chest prototype on in `storage_room`, checks that it caches and draws all chests, and compares
     * the picture with the vanilla screenshot taken just before. Also logs a short benchmark with and without it.
     */
    private fun checkChestPrototype(context: ClientGameTestContext, vanilla: Path) {
        // The same path as the settings screen: change the config, then re-resolve the module states.
        context.runOnClient<RuntimeException> {
            config.cache.prototype = true
            ModuleStates.refresh("gametest")
        }
        context.waitTicks(SETTLE_TICKS)
        context.runOnClient<RuntimeException> {
            check(ChestPrototype.active) {
                "The chest prototype did not start: ${ModuleStates.status(Module.STATIC_CACHE).describe()}"
            }
            check(ChestPrototype.cachedCount == CHESTS) { "Expected $CHESTS cached chests, got ${ChestPrototype.cachedCount}" }
            check(ChestPrototype.lastDrawn == CHESTS) { "Expected $CHESTS drawn chests, got ${ChestPrototype.lastDrawn}" }
        }
        val cached = context.takeScreenshot("scene_storage_room_chest_prototype")
        ScreenshotDiff.compare(vanilla, cached).also { diff ->
            LOGGER.info("Chest prototype screenshot diff: {}", diff)
            check(diff.differingFraction <= MAX_DIFFERING_FRACTION) {
                "The chest prototype changes the picture: $diff (see ${diff.diffImage})"
            }
        }

        // Back to the default state; /mobium gatea enables and restores the prototype itself.
        context.runOnClient<RuntimeException> {
            config.cache.prototype = false
            ModuleStates.refresh("gametest")
        }
        val gate = runPlan(context, "mobium gatea $BENCH_SECONDS 1", "gatea-")
        val setups = gate.drop(1).filter { !it.startsWith("#") }.map { it.split(',')[1] }
        check(setups.size == 2 && setups.count { it.endsWith("+chestcache") } == 1) { "Unexpected Gate A rows: $gate" }
        val speedUp = gate.single { it.startsWith("# gate_a_speedup") }.substringAfter(',').toDouble()
        LOGGER.info("storage_room first look (/mobium gatea {} 1): {}x faster with the chest prototype", BENCH_SECONDS, speedUp)
        context.runOnClient<RuntimeException> {
            check(!ChestPrototype.active && !config.cache.prototype) { "/mobium gatea did not restore the prototype state" }
        }
    }

    /**
     * Runs a `/mobium suite` or `/mobium gatea` command and returns the lines of the medians file it writes. The file
     * is rewritten after every run, so wait for the plan to finish before reading it.
     */
    private fun runPlan(context: ClientGameTestContext, command: String, filePrefix: String): List<String> {
        val directory = benchDirectory(context)
        fun planFiles() = if (!Files.isDirectory(directory)) emptyList() else Files.list(directory).use { files ->
            files.filter { it.fileName.toString().startsWith(filePrefix) }.toList()
        }
        val before = planFiles().toSet()
        context.runOnClient<RuntimeException> { checkNotNull(it.player).connection.sendUnattendedCommand(command, null) }
        context.waitFor({ !BenchSuite.isActive && (planFiles() - before).isNotEmpty() }, PLAN_TIMEOUT_TICKS)
        return Files.readAllLines((planFiles() - before).single())
    }

    /** Runs `/mobium bench <scene>` for [BENCH_SECONDS] and returns its row of summary.csv. */
    private fun runBench(context: ClientGameTestContext, scene: String): Map<String, String> {
        val summary = benchDirectory(context).resolve("summary.csv")
        val rowsBefore = if (Files.exists(summary)) Files.readAllLines(summary).size else 1
        context.runOnClient<RuntimeException> {
            checkNotNull(it.player).connection.sendUnattendedCommand("mobium bench $scene $BENCH_SECONDS", null)
        }
        context.waitFor(
            { Files.exists(summary) && Files.readAllLines(summary).size > rowsBefore },
            (BENCH_SECONDS + 5 + 10) * 20
        )
        val lines = Files.readAllLines(summary)
        return splitCsv(lines.first()).zip(splitCsv(lines.last())).toMap()
    }

    /** Splits one CSV line; quoted fields may contain commas (GPU names such as "llvmpipe (LLVM 20.1.8, 256 bits)"). */
    private fun splitCsv(line: String): List<String> {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                quoted && char == '"' && line.getOrNull(index + 1) == '"' -> { field.append('"'); index++ }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { fields += field.toString(); field.setLength(0) }
                else -> field.append(char)
            }
            index++
        }
        fields += field.toString()
        return fields
    }

    private fun benchDirectory(context: ClientGameTestContext): Path =
        context.computeOnClient<Path, RuntimeException> { it.gameDirectory.toPath().resolve("mobium-bench") }

    /** Runs `/mobium suite` on the control scene and checks its medians file. */
    private fun checkSuiteCommand(context: ClientGameTestContext) {
        val medians = runPlan(context, "mobium suite $BENCH_SECONDS 1 control", "suite-")
        check(medians.size == 2 && medians[1].startsWith("control,")) { "Unexpected suite medians: $medians" }
    }

    /** Runs `/mobium bench` on the last scene (live mobs) and checks the files it writes. */
    private fun checkBenchCommand(context: ClientGameTestContext) {
        val directory = benchDirectory(context)
        val row = runBench(context, "mob_pen_live")
        check(row.getValue("scene") == "mob_pen_live") { "Unexpected scene in $row" }
        check(row.getValue("frames").toInt() > 0) { "No frames recorded: $row" }
        check(row.getValue("resolution").matches(Regex("\\d+x\\d+"))) { "Window size not recorded: $row" }
        check(row.getValue("entities").toInt() >= MOBS) { "Expected at least $MOBS entities: $row" }
        for (column in listOf("extract_entities_ms", "submit_entities_ms", "pose_entities_ms", "tessellate_entities_ms")) {
            check(row.getValue(column).toDouble() > 0.0) { "$column was not measured: $row" }
        }

        val frames = Files.list(directory).use { files ->
            files.filter { it.fileName.toString().startsWith("mob_pen_live-") }.toList()
        }
        check(frames.size == 1 && Files.readAllLines(frames.single()).size > 1) { "Per-frame CSV missing: $frames" }
    }

    private fun installPack(singleplayer: TestSingleplayerContext, pack: Path) {
        val target = singleplayer.worldSave.saveDirectory.resolve("datapacks").resolve(NAMESPACE)
        pack.walk().forEach { source ->
            val destination = target.resolve(pack.relativize(source).toString())
            if (source.isDirectory()) destination.createDirectories() else {
                destination.parent.createDirectories()
                source.copyTo(destination, overwrite = true)
            }
        }
        // A reload picks up and enables packs that appeared in the world's datapacks folder.
        singleplayer.server.runCommand("reload")
        singleplayer.server.waitFor { server ->
            server.functions.get(Identifier.fromNamespaceAndPath(NAMESPACE, "control")).isPresent
        }
    }

    private fun checkOriginIsClear(singleplayer: TestSingleplayerContext) {
        val distance = singleplayer.server.computeOnServer<Double, RuntimeException> { server ->
            val level = checkNotNull(server.getLevel(Level.OVERWORLD))
            val villages = TagKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("village"))
            val nearest = level.findNearestMapStructure(villages, ORIGIN, 64, false)
                ?: return@computeOnServer Double.MAX_VALUE
            val dx = (nearest.x - ORIGIN.x).toDouble()
            val dz = (nearest.z - ORIGIN.z).toDouble()
            sqrt(dx * dx + dz * dz)
        }
        check(distance >= MIN_VILLAGE_DISTANCE) {
            "A village is $distance blocks from the scene origin $ORIGIN; benchmark scenes need $MIN_VILLAGE_DISTANCE"
        }
    }

    /** True once the client sees every framed map of map_wall and has received its map data. */
    private fun mapsSynced(minecraft: Minecraft): Boolean {
        val level = minecraft.level ?: return false
        var maps = 0
        for (entity in level.entitiesForRendering()) {
            val frame = entity as? ItemFrame ?: continue
            val id = frame.item.get(DataComponents.MAP_ID) ?: continue
            if (level.getMapData(id) == null) return false
            maps++
        }
        return maps >= MAP_FRAMES
    }

    private fun isReady(server: net.minecraft.server.MinecraftServer): Boolean {
        val objective = server.scoreboard.getObjective(OBJECTIVE) ?: return false
        return server.scoreboard.getPlayerScoreInfo(ScoreHolder.forNameOnly("#ready"), objective)?.value() == 1
    }

    private companion object {
        const val PACK_PROPERTY = "mobium.benchmarkPack"
        const val NAMESPACE = "mobium-bench"
        const val OBJECTIVE = "mobium_bench"
        const val SEED = "mobium"
        const val MIN_VILLAGE_DISTANCE = 400.0
        const val SCENE_TIMEOUT_TICKS = 1200
        const val SETTLE_TICKS = 60
        const val MAP_FRAMES = 400
        const val MOBS = 500
        const val BENCH_SECONDS = 5
        const val MAP_SYNC_TIMEOUT_TICKS = 600
        const val CHESTS = 1000
        const val PLAN_TIMEOUT_TICKS = 20 * 120

        // Cached chests are drawn from section-local vertices with the section origin in the model-view matrix, so
        // positions differ from vanilla's by float rounding at most; allow a handful of edge pixels.
        const val MAX_DIFFERING_FRACTION = 0.001

        val LOGGER: Logger = LoggerFactory.getLogger("Mobium")

        // Must match X0 / Z0 in benchmark/generate.py.
        val ORIGIN = BlockPos(-3840, -60, -2816)
        val SCENES = listOf("control", "storage_room", "map_wall", "display_build", "mob_pen_static", "mob_pen_live")
    }
}
