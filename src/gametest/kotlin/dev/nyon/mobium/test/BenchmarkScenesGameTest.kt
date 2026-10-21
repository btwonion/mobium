package dev.nyon.mobium.test

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import dev.nyon.mobium.bench.BenchSuite
import dev.nyon.mobium.cache.ArmorStands
import dev.nyon.mobium.cache.Displays
import dev.nyon.mobium.cache.ItemFrames
import dev.nyon.mobium.cache.Lids
import dev.nyon.mobium.cache.Pots
import dev.nyon.mobium.cache.Shelves
import dev.nyon.mobium.cache.Signs
import dev.nyon.mobium.cache.Skulls
import dev.nyon.mobium.cache.StaticCache
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
import net.minecraft.server.level.ParticleStatus
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
                // Particles (the smoke of the mobs a scene reset removes, the ender chest's) differ between two
                // screenshots on their own.
                it.options.particles().set(ParticleStatus.MINIMAL)
            }
            // The baselines are vanilla's pictures; each static scene is then compared with the cache on.
            setCache(context, false)

            // The gallery comes before the mob pens: the smoke of 500 removed mobs would still be in its pictures.
            for (scene in SCENES.take(4) + GALLERY + SCENES.drop(4)) {
                singleplayer.server.runCommand("execute as @a at @s run function $NAMESPACE:$scene")
                singleplayer.server.waitFor({ server -> isReady(server) }, SCENE_TIMEOUT_TICKS)
                singleplayer.connection.waitForChunksRender()
                if (scene == "map_wall") context.waitFor({ mapsSynced(it) }, MAP_SYNC_TIMEOUT_TICKS)
                context.waitTicks(SETTLE_TICKS)
                // The gallery's powered dragon head moves its mouth every tick; frozen ticks keep it still.
                if (scene == GALLERY) singleplayer.server.runCommand("tick freeze")
                val vanilla = context.takeScreenshot("scene_$scene")
                if (scene in COMPARED_SCENES) compareWithCache(context, scene, vanilla)
                if (scene == GALLERY) singleplayer.server.runCommand("tick unfreeze")
                if (scene == "storage_room") checkGateA(context)
            }

            checkBenchCommand(context)
            checkDebugEntry(context)
            checkSuiteCommand(context)
            checkGateB(context)
            setCache(context, true)
        }
    }

    /** The same path as the settings screen: change the config, then re-resolve the module states. */
    private fun setCache(context: ClientGameTestContext, enabled: Boolean) {
        context.runOnClient<RuntimeException> {
            config.cache.enabled = enabled
            ModuleStates.refresh("gametest")
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
     * Switches the static cache on, checks what it caches in `storage_room` and the gallery, and compares the picture
     * with the vanilla screenshot taken just before: no pixel may change beyond isolated edge pixels. The gallery's
     * ender chest lights its surroundings, and vanilla flickers block light every client tick, so there only pixels
     * that differ by more than [ScreenshotDiff.THRESHOLD] count (the self-check compares it exactly, from two frames
     * of one tick).
     */
    private fun compareWithCache(context: ClientGameTestContext, scene: String, vanilla: Path) {
        setCache(context, true)
        context.waitTicks(SETTLE_TICKS)
        context.runOnClient<RuntimeException> {
            check(StaticCache.active) { "The static cache did not start: ${ModuleStates.status(Module.STATIC_CACHE).describe()}" }
            when (scene) {
                "storage_room" -> {
                    check(StaticCache.cachedCount(Lids.key) == CHESTS) { "Expected $CHESTS cached chests: ${StaticCache.debugLine()}" }
                    check(StaticCache.cachedCount(Signs.key) == SIGNS) { "Expected $SIGNS cached signs: ${StaticCache.debugLine()}" }
                    check(StaticCache.lastDrawn >= CHESTS) { "Expected at least $CHESTS drawn objects: ${StaticCache.debugLine()}" }
                }
                GALLERY -> {
                    val counts = listOf(Lids, Skulls, Pots, Signs).map { rule -> StaticCache.cachedCount(rule.key) }
                    check(counts == listOf(10, 7, 2, 10)) { "Unexpected gallery counts $counts: ${StaticCache.debugLine()}" }
                    check(StaticCache.cachedCount(Shelves.key) >= 2) { "Shelves not cached: ${StaticCache.debugLine()}" }
                    // 7 frames (the map stays vanilla; the compass may pause while its needle settles), 6 armor
                    // stands (armor, name tag and glowing stay vanilla), 6 displays (billboard and stained glass stay
                    // vanilla).
                    check(StaticCache.cachedCount(ItemFrames.key) >= 5) { "Item frames not cached: ${StaticCache.debugLine()}" }
                    check(StaticCache.cachedCount(ArmorStands.key) == 3) { "Expected 3 cached armor stands: ${StaticCache.debugLine()}" }
                    check(StaticCache.cachedCount(Displays.key) == 4) { "Expected 4 cached displays: ${StaticCache.debugLine()}" }
                }
                // 400 block displays and 100 item displays; shields (a special item renderer) stay vanilla.
                "display_build" -> {
                    check(StaticCache.cachedCount(Displays.key) >= 400) { "Displays not cached: ${StaticCache.debugLine()}" }
                }
            }
        }
        val cached = context.takeScreenshot("scene_${scene}_static_cache")
        setCache(context, false)
        ScreenshotDiff.compare(vanilla, cached).also { diff ->
            LOGGER.info("Static cache screenshot diff ({}): {}", scene, diff)
            val fraction = if (scene == GALLERY) diff.differingFraction else diff.changedFraction
            check(fraction <= MAX_DIFFERING_FRACTION) { "The static cache changes $scene: $diff (see ${diff.diffImage})" }
        }
    }

    /** A short `/mobium gatea` in `storage_room`, which switches the cache itself and restores it afterwards. */
    private fun checkGateA(context: ClientGameTestContext) {
        val gate = runPlan(context, "mobium gatea $BENCH_SECONDS 1", "gatea-")
        val setups = gate.drop(1).filter { !it.startsWith("#") }.map { it.split(',')[1] }
        check(setups.size == 2 && setups.count { it.endsWith("+cache") } == 1) { "Unexpected Gate A rows: $gate" }
        val speedUp = gate.single { it.startsWith("# gate_a_speedup") }.substringAfter(',').toDouble()
        LOGGER.info("storage_room first look (/mobium gatea {} 1): {}x faster with the static cache", BENCH_SECONDS, speedUp)
        context.runOnClient<RuntimeException> {
            check(!StaticCache.active && !config.cache.enabled) { "/mobium gatea did not restore the cache state" }
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
        // The last row rather than the row count: a summary.csv with older columns is renamed and starts over.
        val lastBefore = if (Files.exists(summary)) Files.readAllLines(summary).lastOrNull() else null
        context.runOnClient<RuntimeException> {
            checkNotNull(it.player).connection.sendUnattendedCommand("mobium bench $scene $BENCH_SECONDS", null)
        }
        context.waitFor(
            { Files.exists(summary) && Files.readAllLines(summary).let { it.size > 1 && it.last() != lastBefore } },
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

    /**
     * A short `/mobium gateb`: four runs (`storage_room` with the cache, with its draw calls skipped, `control` with and
     * without the cache), Gate B's numbers, and the cache state restored afterwards.
     */
    private fun checkGateB(context: ClientGameTestContext) {
        val gate = runPlan(context, "mobium gateb $BENCH_SECONDS 1", "gateb-")
        val rows = gate.drop(1).filter { !it.startsWith("#") }.map { it.split(',').let { fields -> fields[0] to fields[1] } }
        check(
            rows.size == 4 && rows.count { it.second.endsWith("+cache") } == 2 &&
                rows.single { it.second.endsWith("+cache+nodraw") }.first == "storage_room"
        ) { "Unexpected Gate B rows: $gate" }
        fun value(name: String) = gate.single { it.startsWith("# $name,") }.substringAfter(',').toDouble()
        LOGGER.info(
            "Gate B first look (/mobium gateb {} 1): cached objects {}x control, whole storage_room frame {}x control, " +
                "drawing them {} ms, control {}x with the cache",
            BENCH_SECONDS, value("gate_b_cost_over_control"), value("gate_b_storage_over_control"),
            value("gate_b_drawing_ms"), value("gate_b_control_cache_over_vanilla")
        )
        context.runOnClient<RuntimeException> {
            check(!StaticCache.active && !config.cache.enabled && !StaticCache.skipDraws) {
                "/mobium gateb did not restore the cache state"
            }
        }
    }

    /** Runs `/mobium suite` on the control scene and checks its medians file. */
    private fun checkSuiteCommand(context: ClientGameTestContext) {
        val medians = runPlan(context, "mobium suite $BENCH_SECONDS 1 control", "suite-")
        check(
            medians.size == 3 && medians[1].startsWith("control,") && medians[2].startsWith("# build,") &&
                !medians[2].endsWith(",unknown")
        ) {
            "Unexpected suite medians: $medians"
        }
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
        const val SIGNS = 300
        const val PLAN_TIMEOUT_TICKS = 20 * 120

        // Cached objects are drawn from camera-relative vertices computed as local position + (origin - camera), so
        // positions differ from vanilla's by float rounding at most; allow a handful of edge pixels.
        const val MAX_DIFFERING_FRACTION = 0.001

        val LOGGER: Logger = LoggerFactory.getLogger("Mobium")

        // Must match X0 / Z0 in benchmark/generate.py.
        val ORIGIN = BlockPos(-3840, -60, -2816)
        val SCENES = listOf("control", "storage_room", "map_wall", "display_build", "mob_pen_static", "mob_pen_live")

        /** Not a benchmark scene: every cached type side by side (benchmark/generate.py). */
        const val GALLERY = "cache_gallery"

        /** The scenes where nothing moves, so vanilla's and the cache's pictures can be compared. */
        val COMPARED_SCENES = setOf("control", "storage_room", "map_wall", "display_build", GALLERY)
    }
}
