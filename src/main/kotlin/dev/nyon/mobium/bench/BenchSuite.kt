package dev.nyon.mobium.bench

import dev.nyon.mobium.Mobium
import dev.nyon.mobium.cache.ChestPrototype
import dev.nyon.mobium.config.config
import dev.nyon.mobium.debug.Sections
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.scores.ScoreHolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs benchmark plans unattended, so a whole setup is one command:
 * - `/mobium suite [seconds] [runs] [scenes…]`: every scene (default all six), each built with the benchmark datapack,
 *   settled for at least [SETTLE_SECONDS] and until section rebuilds, light updates and server ticks have calmed down,
 *   then recorded `runs` times;
 * - `/mobium gatea [seconds] [runs]`: `storage_room` with the M3 chest prototype off and on, alternating, then the
 *   speed-up against the 1.5× target of Gate A.
 *
 * Scenes are built through the integrated server (singleplayer only), so the world does not need commands enabled.
 * The medians per scene and setup are rewritten to `mobium-bench/suite-<timestamp>.csv` after every run, so quitting
 * halfway keeps what was measured, and printed at the end; every single run is also in `summary.csv` as usual. A plan
 * does not start when the instance declares another setup than the one that is active ([BenchEnvironment]). Ticked
 * once per client tick on the render thread.
 */
object BenchSuite {
    const val DEFAULT_SECONDS = 60
    const val DEFAULT_RUNS = 3
    const val SETTLE_SECONDS = 10
    const val GATE_A_TARGET = 1.5
    val SCENES = listOf("mob_pen_static", "mob_pen_live", "storage_room", "map_wall", "display_build", "control")

    private const val NAMESPACE = "mobium-bench"
    private const val OBJECTIVE = "mobium_bench"
    private const val BUILD_TIMEOUT_SECONDS = 120L
    private const val TOGGLE_SETTLE_SECONDS = 3L
    private const val QUIET_SECONDS = 3L
    private const val QUIET_TIMEOUT_SECONDS = 120L
    private const val QUIET_TICK_NANOS = 25_000_000L
    private const val NANOS_PER_SECOND = 1_000_000_000L

    /** One benchmark run of the plan; [prototype] null leaves the chest prototype as it is. */
    private class Step(val scene: String, val prototype: Boolean?)

    private enum class State { IDLE, BUILDING, SETTLING, RECORDING }

    private var state = State.IDLE
    private val steps = ArrayList<Step>()
    private var index = 0
    private var seconds = DEFAULT_SECONDS
    private var builtScene: String? = null
    private var deadline = 0L
    private var nextPoll = 0L
    private var runsBefore = 0
    private var gateA = false
    private var prototypeConfigBefore = false
    private val ready = AtomicBoolean(false)
    private var waitForQuiet = false
    private var quietSince = 0L
    private var settleTimeout = 0L
    private val missingPack = AtomicBoolean(false)
    private val results = ArrayList<BenchResult>()
    private var stamp = ""

    val isActive: Boolean
        get() = state != State.IDLE

    /** Starts `/mobium suite`; returns an error message or null. */
    fun startSuite(seconds: Int, runs: Int, scenes: List<String>): Component? {
        val unknown = scenes.firstOrNull { it !in SCENES }
        if (unknown != null) return Component.translatable("mobium.suite.unknown_scene", unknown)
        val plan = scenes.flatMap { scene -> List(runs) { Step(scene, null) } }
        start(plan, seconds, gateA = false)?.let { return it }
        chat(Component.translatable("mobium.suite.started", scenes.size, runs, seconds, minutes(plan.size, scenes.size)))
        return null
    }

    /** Starts `/mobium gatea`; returns an error message or null. */
    fun startGateA(seconds: Int, runs: Int): Component? {
        val plan = (1..runs).flatMap { listOf(Step("storage_room", false), Step("storage_room", true)) }
        prototypeConfigBefore = config.cache.prototype
        config.cache.prototype = true
        ChestPrototype.unavailableReason()?.let { reason ->
            config.cache.prototype = prototypeConfigBefore
            return Component.translatable(reason)
        }
        start(plan, seconds, gateA = true)?.let { error ->
            config.cache.prototype = prototypeConfigBefore
            return error
        }
        chat(Component.translatable("mobium.suite.gatea_started", runs, seconds, minutes(plan.size, 1)))
        return null
    }

    private fun start(plan: List<Step>, seconds: Int, gateA: Boolean): Component? {
        if (isActive || FrameRecorder.isActive) return Component.translatable("mobium.bench.busy")
        if (Minecraft.getInstance().singleplayerServer == null) return Component.translatable("mobium.suite.singleplayer")
        BenchEnvironment.setupMismatch()?.let { return it }
        BenchEnvironment.warnings(BenchEnvironment.snapshot()).forEach(::chat)
        stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

        steps.clear()
        steps += plan
        results.clear()
        index = 0
        this.seconds = seconds
        this.gateA = gateA
        builtScene = null
        beginStep()
        return null
    }

    /** Aborts the plan (the current run is stopped by `/mobium stop` as well). */
    fun stop(): Boolean {
        if (!isActive) return false
        finishPlan(aborted = true)
        return true
    }

    @JvmStatic
    fun tick(minecraft: Minecraft) {
        if (state == State.IDLE) return
        val now = System.nanoTime()
        when (state) {
            State.BUILDING -> when {
                missingPack.get() -> fail(Component.translatable("mobium.suite.no_datapack"))
                ready.get() -> {
                    state = State.SETTLING
                    deadline = now + SETTLE_SECONDS * NANOS_PER_SECOND
                    waitForQuiet = true
                    quietSince = 0L
                    settleTimeout = now + QUIET_TIMEOUT_SECONDS * NANOS_PER_SECOND
                }
                now > deadline -> fail(Component.translatable("mobium.suite.timeout", steps[index].scene, BUILD_TIMEOUT_SECONDS))
                now >= nextPoll -> poll(minecraft)
            }
            State.SETTLING -> if (now >= deadline) {
                if (!waitForQuiet) {
                    startRun()
                } else if (isQuiet(minecraft)) {
                    if (quietSince == 0L) quietSince = now
                    if (now - quietSince >= QUIET_SECONDS * NANOS_PER_SECOND) startRun()
                } else {
                    quietSince = 0L
                    if (now > settleTimeout) {
                        chat(Component.translatable("mobium.suite.not_quiet", steps[index].scene, QUIET_TIMEOUT_SECONDS))
                        startRun()
                    }
                }
            }
            State.RECORDING -> if (!FrameRecorder.isActive) {
                if (FrameRecorder.completedRuns > runsBefore) {
                    FrameRecorder.lastResult?.let(results::add)
                    writeMedians()
                }
                index++
                if (index >= steps.size) finishPlan(aborted = false) else beginStep()
            }
            State.IDLE -> Unit
        }
    }

    private fun beginStep() {
        val step = steps[index]
        var toggled = false
        if (step.prototype != null && step.prototype != ChestPrototype.active) {
            ChestPrototype.setActive(step.prototype)
            toggled = true
        }
        if (step.scene != builtScene) {
            build(step.scene)
        } else {
            state = State.SETTLING
            deadline = System.nanoTime() + (if (toggled) TOGGLE_SETTLE_SECONDS else 1L) * NANOS_PER_SECOND
        }
    }

    private fun build(scene: String) {
        val minecraft = Minecraft.getInstance()
        val server = minecraft.singleplayerServer
        val player = minecraft.player
        if (server == null || player == null) {
            fail(Component.translatable("mobium.suite.singleplayer"))
            return
        }
        ready.set(false)
        missingPack.set(false)
        val uuid = player.uuid
        server.execute {
            if (server.functions.get(Identifier.fromNamespaceAndPath(NAMESPACE, scene)).isEmpty) {
                missingPack.set(true)
                return@execute
            }
            server.commands.performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(),
                "execute as $uuid at @s run function $NAMESPACE:$scene"
            )
        }
        builtScene = scene
        state = State.BUILDING
        deadline = System.nanoTime() + BUILD_TIMEOUT_SECONDS * NANOS_PER_SECOND
        nextPoll = 0L
    }

    /** Asks the server whether the datapack set `#ready` to 1; runs after the build command in the server's queue. */
    private fun poll(minecraft: Minecraft) {
        nextPoll = System.nanoTime() + NANOS_PER_SECOND / 2
        val server = minecraft.singleplayerServer ?: return
        server.execute { ready.set(isReady(server)) }
    }

    private fun isReady(server: MinecraftServer): Boolean {
        val objective = server.scoreboard.getObjective(OBJECTIVE) ?: return false
        return server.scoreboard.getPlayerScoreInfo(ScoreHolder.forNameOnly("#ready"), objective)?.value() == 1
    }

    /**
     * A freshly built scene keeps the game busy for a while (section rebuilds, client light updates, server lighting);
     * measuring before that ends would penalise whichever run comes first.
     */
    private fun isQuiet(minecraft: Minecraft): Boolean {
        val level = minecraft.level ?: return false
        if (!minecraft.levelRenderer.hasRenderedAllSections() || level.lightEngine.hasLightWork()) return false
        val server = minecraft.singleplayerServer ?: return true
        return server.averageTickTimeNanos < QUIET_TICK_NANOS
    }

    private fun startRun() {
        waitForQuiet = false
        runsBefore = FrameRecorder.completedRuns
        if (!FrameRecorder.start(steps[index].scene, seconds, null, quiet = true)) {
            fail(Component.translatable("mobium.bench.busy"))
            return
        }
        state = State.RECORDING
    }

    private fun fail(message: Component) {
        chat(Component.translatable("mobium.suite.failed", message))
        finishPlan(aborted = true)
    }

    private fun finishPlan(aborted: Boolean) {
        state = State.IDLE
        if (FrameRecorder.isActive) FrameRecorder.stop()
        if (gateA) {
            ChestPrototype.setActive(false)
            config.cache.prototype = prototypeConfigBefore
            if (prototypeConfigBefore) ChestPrototype.setActive(true)
        }
        if (aborted) {
            chat(Component.translatable("mobium.suite.stopped"))
            if (results.isEmpty()) return
        }
        report()
    }

    /** Median of every run of one scene and setup. */
    private class Medians(val scene: String, val setup: String, val runs: Int, val avgMs: Double, val avgFps: Double, val low1Fps: Double)

    private fun medians(): List<Medians> {
        val groups = LinkedHashMap<Pair<String, String>, MutableList<BenchResult>>()
        for (result in results) groups.getOrPut(result.scene to result.setup) { ArrayList() } += result
        return groups.map { (key, runs) ->
            Medians(
                key.first,
                key.second,
                runs.size,
                FrameStats.median(DoubleArray(runs.size) { runs[it].summary.avgMs }),
                FrameStats.median(DoubleArray(runs.size) { runs[it].summary.avgFps }),
                FrameStats.median(DoubleArray(runs.size) { runs[it].summary.low1Fps })
            )
        }
    }

    /** Gate A's speed-up from the medians, or null while one of the two sides has no run yet. */
    private fun gateASpeedUp(medians: List<Medians>): Double? {
        val off = medians.firstOrNull { !it.setup.endsWith("+chestcache") }?.avgMs
        val on = medians.firstOrNull { it.setup.endsWith("+chestcache") }?.avgMs
        return if (off != null && on != null && on > 0.0) off / on else null
    }

    /** Rewrites `suite-<stamp>.csv` (or `gatea-<stamp>.csv`) with the medians of the runs so far; null on failure. */
    private fun writeMedians(): Path? {
        val rows = StringBuilder()
        rows.append("scene,setup,runs,avg_ms,avg_fps,low1_fps,p95_ms")
        for (name in Sections.NAMES) rows.append(",${name}_ms")
        rows.append('\n')
        val groups = LinkedHashMap<Pair<String, String>, MutableList<BenchResult>>()
        for (result in results) groups.getOrPut(result.scene to result.setup) { ArrayList() } += result
        for ((key, runs) in groups) {
            val avgMs = FrameStats.median(DoubleArray(runs.size) { runs[it].summary.avgMs })
            val avgFps = FrameStats.median(DoubleArray(runs.size) { runs[it].summary.avgFps })
            val low1 = FrameStats.median(DoubleArray(runs.size) { runs[it].summary.low1Fps })
            val p95 = FrameStats.median(DoubleArray(runs.size) { runs[it].summary.p95Ms })
            rows.append("${key.first},${key.second},${runs.size},${fmt(avgMs, 4)},${fmt(avgFps, 2)},${fmt(low1, 2)},${fmt(p95, 4)}")
            for (section in 0 until Sections.COUNT) {
                rows.append(',').append(fmt(FrameStats.median(DoubleArray(runs.size) { runs[it].sectionMeansMs[section] }), 4))
            }
            rows.append('\n')
        }
        if (gateA) gateASpeedUp(medians())?.let { rows.append("# gate_a_speedup,${fmt(it, 3)}\n") }

        return try {
            val directory = Minecraft.getInstance().gameDirectory.toPath().resolve("mobium-bench")
            Files.createDirectories(directory)
            val file = directory.resolve(if (gateA) "gatea-$stamp.csv" else "suite-$stamp.csv")
            Files.writeString(file, rows)
            file
        } catch (e: IOException) {
            Mobium.LOGGER.error("Could not write the suite medians", e)
            chat(Component.translatable("mobium.bench.failed", e.message ?: e.javaClass.simpleName))
            null
        }
    }

    private fun report() {
        val medians = medians()
        for (median in medians) {
            chat(
                Component.translatable(
                    "mobium.suite.result",
                    median.scene, median.setup, fmt(median.avgMs, 2), fmt(median.avgFps, 1), fmt(median.low1Fps, 1), median.runs
                )
            )
        }

        if (gateA) {
            gateASpeedUp(medians)?.let { speedUp ->
                val verdict = if (speedUp >= GATE_A_TARGET) "mobium.suite.gatea_pass" else "mobium.suite.gatea_below"
                chat(Component.translatable("mobium.suite.gatea_result", fmt(speedUp, 2), fmt(GATE_A_TARGET, 1), Component.translatable(verdict)))
                Mobium.LOGGER.info("Gate A: {}x faster with the chest prototype (target {}x)", fmt(speedUp, 2), GATE_A_TARGET)
            }
        }

        writeMedians()?.let { chat(Component.translatable("mobium.suite.saved", it.toString())) }
    }

    private fun minutes(steps: Int, scenes: Int): Int {
        val perRun = seconds + FrameRecorder.WARMUP_SECONDS + 1
        return ((steps * perRun + scenes * (SETTLE_SECONDS + 5)) / 60.0).let { kotlin.math.ceil(it).toInt() }
    }

    private fun chat(message: Component) = Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(message)

    private fun fmt(value: Double, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", value)
}
