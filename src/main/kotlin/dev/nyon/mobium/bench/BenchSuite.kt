package dev.nyon.mobium.bench

import dev.nyon.mobium.Mobium
import dev.nyon.mobium.cache.StaticCache
import dev.nyon.mobium.config.config
import dev.nyon.mobium.core.Capabilities
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.CompatMod
import dev.nyon.mobium.core.IrisCompat
import dev.nyon.mobium.core.Module
import dev.nyon.mobium.core.ModuleStates
import dev.nyon.mobium.core.ModuleStatus
import dev.nyon.mobium.core.OffReason
import dev.nyon.mobium.debug.AbToggle
import dev.nyon.mobium.debug.Sections
import dev.nyon.mobium.parts.ExpandPass
import dev.nyon.mobium.parts.PartTransform
import net.minecraft.ChatFormatting
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.debug.DebugEntrySystemSpecs
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.FlatLevelSource
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Runs benchmark plans unattended, so a whole setup is one command:
 * - `/mobium suite [seconds] [runs] [scenes…]`: every scene (default all six), each built with the benchmark datapack,
 *   settled for at least [SETTLE_SECONDS] and until section rebuilds, light updates and server ticks have calmed down,
 *   then recorded `runs` times;
 * - `/mobium gatea [seconds] [runs]`: `storage_room` with the static cache off and on, alternating, then the
 *   speed-up against the 1.5× target of Gate A (passed with the M3 chest prototype; kept for comparisons);
 * - `/mobium gateb [seconds] [runs]`: `storage_room` with the cache, the same with the cache's draw calls skipped,
 *   `control` with the cache and `control` without it, in turn, then Gate B's numbers ([GateB]): what the cached
 *   objects cost Mobium against `control` (target at most 1.2×), the whole frame against `control`, what drawing them
 *   costs, and `control` with the cache against `control` without it (must not be slower);
 * - `/mobium gatec [seconds] [runs]`: `mob_pen_live` with Module 2 off and on, alternating, then Gate C's numbers
 *   ([GateC]): the entity CPU work without over with Module 2 (target at least 1.5× in setup D) and the frame time
 *   with over without (must not be slower);
 * - `/mobium stages [seconds] [runs] [scene]`: a scene (default `mob_pen_live`) with Module 2's expand pass as a
 *   compute dispatch and in the vertex stage, alternating ([ExpandPass]), then the frame time of the vertex stage over
 *   compute's;
 * - `/mobium benchmark`: the players' benchmark, in a superflat world made with the built-in Mobium Benchmark data
 *   pack ([BenchPack]): `storage_room` and `mob_pen_live` with all of Mobium off, on, on and off ([AbToggle], as the A/B
 *   key does), [BENCHMARK_SECONDS] each, with [BenchOptions], then each scene's gain in chat and one CSV with the
 *   medians, the gains and what the result depends on (CPU, GPU, window, power).
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
    const val BENCHMARK_SECONDS = 30
    val BENCHMARK_SCENES = listOf("storage_room", "mob_pen_live")

    /** Mobium off, on, on, off in each scene: drift (a laptop warming up) then favours neither side. */
    private val BENCHMARK_ORDER = booleanArrayOf(false, true, true, false)

    /** A gain below this, in percent, is within what two runs per side can tell apart. */
    private const val BENCHMARK_NOISE_PERCENT = 2.0

    /** The first air block of the scenes (benchmark/generate.py's `GROUND`): classic superflat's surface. */
    private const val SCENE_GROUND = -60
    private const val BENCHMARK_SEED = "mobium"
    private const val ISSUE_URL = "https://github.com/btwonion/mobium/issues/new?template=benchmark.yml"
    private const val DISCORD_URL = "https://nyon.dev/discord"

    private const val BUILD_TIMEOUT_SECONDS = 120L
    private const val TOGGLE_SETTLE_SECONDS = 3L
    private const val QUIET_SECONDS = 3L
    private const val QUIET_TIMEOUT_SECONDS = 120L
    private const val NANOS_PER_SECOND = 1_000_000_000L

    /**
     * One benchmark run of the plan; [cache] and [parts] null leave the static cache and Module 2 as they are, [stage]
     * null leaves Module 2's expand pass to its own choice ([ExpandPass.stage]).
     * [skipDraws] records the cached objects without drawing them (`StaticCache.skipDraws`; the setup is then named
     * `…+cache+nodraw`). [mobium] switches all of Mobium off or on through the A/B state, which then decides the
     * modules as in normal play.
     */
    private class Step(
        val scene: String,
        val cache: Boolean?,
        val skipDraws: Boolean = false,
        val parts: Boolean? = null,
        val stage: Int? = null,
        val mobium: Boolean? = null
    )

    private enum class State { IDLE, BUILDING, SETTLING, RECORDING }

    /** What a plan is for: medians only, or one of the gates' verdicts. */
    private enum class Kind(val filePrefix: String) {
        SUITE("suite"), GATE_A("gatea"), GATE_B("gateb"), GATE_C("gatec"), STAGES("stages"), BENCHMARK("benchmark")
    }

    private var state = State.IDLE
    private val steps = ArrayList<Step>()
    private var index = 0
    private var seconds = DEFAULT_SECONDS
    private var builtScene: String? = null
    private var deadline = 0L
    private var nextPoll = 0L
    private var runsBefore = 0
    private var kind = Kind.SUITE
    private var cacheConfigBefore = true
    private var partsConfigBefore = true
    private var waitForQuiet = false
    private var quietSince = 0L
    private var settleTimeout = 0L
    private val scenes = SceneBuilder()
    private val results = ArrayList<BenchResult>()

    /** Per entry of [results]: whether Mobium was on (`/mobium benchmark`). */
    private val resultsMobium = ArrayList<Boolean>()
    private var stamp = ""
    private var abOffBefore = false
    private var environment: BenchEnvironment.Snapshot? = null
    private val benchmarkNotes = ArrayList<String>()

    val isActive: Boolean
        get() = state != State.IDLE

    /** True while a plan switches the modules itself; `/mobium benchmark` leaves that to the A/B state. */
    val decidesModules: Boolean
        get() = isActive && kind != Kind.BENCHMARK

    /** Starts `/mobium suite`; returns an error message or null. */
    fun startSuite(seconds: Int, runs: Int, scenes: List<String>): Component? {
        val unknown = scenes.firstOrNull { it !in SCENES }
        if (unknown != null) return Component.translatable("mobium.suite.unknown_scene", unknown)
        val plan = scenes.flatMap { scene -> List(runs) { Step(scene, null) } }
        start(plan, seconds, Kind.SUITE)?.let { return it }
        chat(Component.translatable("mobium.suite.started", scenes.size, runs, seconds, minutes(plan.size, scenes.size)))
        return null
    }

    /** Starts `/mobium gatea`; returns an error message or null. */
    fun startGateA(seconds: Int, runs: Int): Component? {
        val plan = (1..runs).flatMap { listOf(Step("storage_room", false), Step("storage_room", true)) }
        startGate(plan, seconds, Kind.GATE_A, "mobium.suite.gatea_unavailable")?.let { return it }
        chat(Component.translatable("mobium.suite.gatea_started", runs, seconds, minutes(plan.size, 1)))
        return null
    }

    /** Starts `/mobium gateb`; returns an error message or null. */
    fun startGateB(seconds: Int, runs: Int): Component? {
        val plan = (1..runs).flatMap {
            listOf(
                Step("storage_room", true),
                Step("storage_room", true, skipDraws = true),
                Step("control", true),
                Step("control", false)
            )
        }
        startGate(plan, seconds, Kind.GATE_B, "mobium.suite.gateb_unavailable")?.let { return it }
        chat(Component.translatable("mobium.suite.gateb_started", runs, seconds, minutes(plan.size, plan.size / 2)))
        return null
    }

    /** Starts `/mobium gatec`; returns an error message or null. */
    fun startGateC(seconds: Int, runs: Int): Component? {
        val plan = (1..runs).flatMap { listOf(Step("mob_pen_live", null, parts = false), Step("mob_pen_live", null, parts = true)) }
        startGate(plan, seconds, Kind.GATE_C, "mobium.suite.gatec_unavailable", Module.PART_TRANSFORM)?.let { return it }
        chat(Component.translatable("mobium.suite.gatec_started", runs, seconds, minutes(plan.size, 1)))
        return null
    }

    /** Starts `/mobium stages`; returns an error message or null. */
    fun startStages(seconds: Int, runs: Int, scene: String): Component? {
        if (scene !in SCENES) return Component.translatable("mobium.suite.unknown_scene", scene)
        if (!ExpandPass.vertexStores) return Component.translatable("mobium.suite.stages_no_vertex")
        val plan = (1..runs).flatMap {
            listOf(
                Step(scene, null, parts = true, stage = ExpandPass.STAGE_COMPUTE),
                Step(scene, null, parts = true, stage = ExpandPass.STAGE_VERTEX)
            )
        }
        startGate(plan, seconds, Kind.STAGES, "mobium.suite.stages_unavailable", Module.PART_TRANSFORM)?.let { return it }
        chat(Component.translatable("mobium.suite.stages_started", scene, runs, seconds, minutes(plan.size, 1)))
        return null
    }

    /**
     * Starts `/mobium benchmark`; returns an error message or null. Refuses outside a classic superflat world with the
     * Mobium Benchmark data pack, so the scenes are never built in a world the player uses, and when Mobium cannot run
     * at all.
     */
    fun startBenchmark(seconds: Int): Component? {
        if (isActive || FrameRecorder.isActive) return Component.translatable("mobium.bench.busy")
        val minecraft = Minecraft.getInstance()
        val server = minecraft.singleplayerServer ?: return Component.translatable("mobium.benchmark.singleplayer")
        val level = minecraft.level ?: return Component.translatable("mobium.benchmark.singleplayer")
        if (level.dimension() != Level.OVERWORLD || !isClassicFlat(server)) return Component.translatable("mobium.benchmark.not_flat")
        if (BENCHMARK_SCENES.any { !SceneBuilder.hasScene(server, it) }) return Component.translatable("mobium.benchmark.no_pack")

        abOffBefore = AbToggle.mobiumOff
        AbToggle.set(false, "benchmark: Mobium")
        val cache = ModuleStates.status(Module.STATIC_CACHE)
        val parts = ModuleStates.status(Module.PART_TRANSFORM)
        if (!cache.active && !parts.active) {
            AbToggle.set(abOffBefore, "benchmark refused")
            return Component.translatable("mobium.benchmark.unavailable", reason(cache))
        }
        cacheConfigBefore = config.cache.enabled
        partsConfigBefore = config.parts.enabled
        environment = BenchEnvironment.snapshot()
        // WorldOptions.parseSeed turns a seed that is not a number into its hash code.
        val seedMobium = server.overworld().seed == BENCHMARK_SEED.hashCode().toLong()
        benchmarkNotes.clear()
        benchmarkNotes += "seed_mobium,$seedMobium"
        benchmarkNotes += "module_static_cache,${cache.describe()}"
        benchmarkNotes += "module_part_transform,${parts.describe()}"

        BenchOptions.apply(minecraft.options)
        val plan = BENCHMARK_SCENES.flatMap { scene -> BENCHMARK_ORDER.map { Step(scene, null, mobium = it) } }
        start(plan, seconds, Kind.BENCHMARK)?.let { error ->
            BenchOptions.restore(minecraft.options)
            AbToggle.set(abOffBefore, "benchmark refused")
            return error
        }
        chat(Component.translatable("mobium.benchmark.started", BENCHMARK_SCENES.size, seconds, minutes(plan.size, BENCHMARK_SCENES.size)))
        chat(
            Component.translatable(
                "mobium.benchmark.options", BenchOptions.RENDER_DISTANCE, BenchOptions.FOV, (BenchOptions.ENTITY_DISTANCE * 100).toInt()
            )
        )
        moduleOff(cache, "storage_room")
        moduleOff(parts, "mob_pen_live")
        if (!seedMobium) chat(Component.translatable("mobium.benchmark.seed", BENCHMARK_SEED))
        return null
    }

    /** The overworld is superflat with classic's layers, so the scenes stand on the ground and under the sky. */
    private fun isClassicFlat(server: MinecraftServer): Boolean {
        val overworld = server.overworld()
        val generator = overworld.chunkSource.generator
        return generator is FlatLevelSource && overworld.minY + generator.settings().layers.size == SCENE_GROUND
    }

    private fun moduleOff(status: ModuleStatus, scene: String) {
        if (status.active) return
        chat(
            Component.translatable(
                "mobium.benchmark.module_off", Component.translatable(status.module.translationKey), reason(status),
                Component.translatable("mobium.benchmark.scene.$scene")
            )
        )
    }

    /** Why a module is off, with the overlapping mod or failure as the reason's detail (as the settings screen shows). */
    private fun reason(status: ModuleStatus): Component = Component.translatable(status.reason!!.translationKey, status.detail)

    /**
     * A gate's plan switches its module itself (the static cache, or Module 2 for Gate C), even if it is off in the
     * settings (which are not saved); the A/B key does not count.
     */
    private fun startGate(
        plan: List<Step>,
        seconds: Int,
        kind: Kind,
        unavailableKey: String,
        module: Module = Module.STATIC_CACHE
    ): Component? {
        cacheConfigBefore = config.cache.enabled
        partsConfigBefore = config.parts.enabled
        if (module == Module.STATIC_CACHE) config.cache.enabled = true else config.parts.enabled = true
        ModuleStates.refresh(kind.name)
        val status = ModuleStates.status(module)
        if (!status.active && status.reason != OffReason.AB_TOGGLE) {
            restoreCache()
            return Component.translatable(unavailableKey, Component.translatable(status.reason!!.translationKey))
        }
        start(plan, seconds, kind)?.let { error ->
            restoreCache()
            return error
        }
        return null
    }

    private fun start(plan: List<Step>, seconds: Int, kind: Kind): Component? {
        if (isActive || FrameRecorder.isActive) return Component.translatable("mobium.bench.busy")
        if (Minecraft.getInstance().singleplayerServer == null) return Component.translatable("mobium.suite.singleplayer")
        BenchEnvironment.setupMismatch()?.let { return it }
        BenchEnvironment.warnings(BenchEnvironment.snapshot(), display = kind != Kind.BENCHMARK).forEach(::chat)
        stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

        steps.clear()
        steps += plan
        results.clear()
        resultsMobium.clear()
        index = 0
        this.seconds = seconds
        this.kind = kind
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
        if (minecraft.level == null) {
            // Left the world: stop, and put back what the plan changed.
            finishPlan(aborted = true)
            return
        }
        val now = System.nanoTime()
        when (state) {
            State.BUILDING -> when {
                scenes.missingPack.get() -> fail(Component.translatable("mobium.suite.no_datapack"))
                scenes.ready.get() -> {
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
                } else if (SceneBuilder.isQuiet(minecraft)) {
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
                    FrameRecorder.lastResult?.let { result ->
                        results += result
                        resultsMobium += steps[index].mobium == true
                    }
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
        if (step.cache != null && step.cache != StaticCache.active) {
            StaticCache.setActive(step.cache)
            toggled = true
        }
        if (step.skipDraws != StaticCache.skipDraws) {
            StaticCache.skipDraws = step.skipDraws
            toggled = true
        }
        if (step.parts != null && step.parts != PartTransform.active) {
            PartTransform.setActive(step.parts)
            toggled = true
        }
        if (step.mobium != null) {
            val cacheBefore = StaticCache.active
            val partsBefore = PartTransform.active
            AbToggle.set(!step.mobium, if (step.mobium) "benchmark: Mobium" else "benchmark: vanilla")
            if (StaticCache.active != cacheBefore || PartTransform.active != partsBefore) toggled = true
        }
        val stage = step.stage ?: -1
        if (stage != ExpandPass.forcedStage) {
            ExpandPass.forcedStage = stage
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
        if (!scenes.build(Minecraft.getInstance(), scene)) {
            fail(Component.translatable("mobium.suite.singleplayer"))
            return
        }
        builtScene = scene
        state = State.BUILDING
        deadline = System.nanoTime() + BUILD_TIMEOUT_SECONDS * NANOS_PER_SECOND
        nextPoll = 0L
    }

    /** Asks the server whether the scene is ready, at most twice per second. */
    private fun poll(minecraft: Minecraft) {
        nextPoll = System.nanoTime() + NANOS_PER_SECOND / 2
        scenes.poll(minecraft)
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
        chat(Component.translatable(if (kind == Kind.BENCHMARK) "mobium.benchmark.failed" else "mobium.suite.failed", message))
        finishPlan(aborted = true)
    }

    private fun finishPlan(aborted: Boolean) {
        state = State.IDLE
        if (FrameRecorder.isActive) FrameRecorder.stop()
        StaticCache.skipDraws = false
        ExpandPass.forcedStage = -1
        if (kind != Kind.SUITE) {
            StaticCache.setActive(false)
            PartTransform.setActive(false)
            if (kind == Kind.BENCHMARK) {
                BenchOptions.restore(Minecraft.getInstance().options)
                AbToggle.set(abOffBefore, "benchmark finished")
            }
            restoreCache()
        }
        if (aborted) {
            chat(Component.translatable(if (kind == Kind.BENCHMARK) "mobium.benchmark.stopped" else "mobium.suite.stopped"))
            if (results.isEmpty()) return
        }
        report()
    }

    /** Median of every run of one scene and setup. */
    private class Medians(
        val scene: String,
        val setup: String,
        val runs: Int,
        val avgMs: Double,
        val avgFps: Double,
        val low1Fps: Double,
        val drawMs: Double,
        val prepareMs: Double,
        val shadowPrepareMs: Double
    )

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
                FrameStats.median(DoubleArray(runs.size) { runs[it].summary.low1Fps }),
                FrameStats.median(DoubleArray(runs.size) { runs[it].sectionMeansMs[Sections.DRAW] }),
                FrameStats.median(DoubleArray(runs.size) { runs[it].sectionMeansMs[Sections.PREPARE] }),
                FrameStats.median(DoubleArray(runs.size) { runs[it].sectionMeansMs[Sections.SHADOW_PREPARE] })
            )
        }
    }

    /** Gate B's numbers from the medians, or null while one of its four runs is missing. */
    private fun gateB(medians: List<Medians>): GateB? {
        fun run(scene: String, suffix: String?): GateB.Run? {
            val median = medians.firstOrNull { median ->
                median.scene == scene && when (suffix) {
                    null -> !median.setup.endsWith(CACHE) && !median.setup.endsWith(NO_DRAW)
                    else -> median.setup.endsWith(suffix)
                }
            } ?: return null
            return GateB.Run(median.avgMs, median.drawMs)
        }
        return GateB.of(run("storage_room", CACHE), run("storage_room", NO_DRAW), run("control", CACHE), run("control", null))
    }

    /** Gate C's numbers from the medians, or null while one of the two sides has no run yet. */
    private fun gateC(medians: List<Medians>): GateC? {
        fun run(parts: Boolean): GateC.Run? {
            val median = medians.firstOrNull { (PARTS in it.setup) == parts } ?: return null
            return GateC.Run(median.avgMs, median.prepareMs, median.shadowPrepareMs)
        }
        return GateC.of(run(false), run(true))
    }

    /** `/mobium stages`: the vertex stage's median frame over compute's, or null while one side has no run yet. */
    private fun stagesRatio(medians: List<Medians>): Double? {
        val compute = medians.firstOrNull { PARTS in it.setup && VERTEX_STAGE !in it.setup }?.avgMs
        val vertex = medians.firstOrNull { VERTEX_STAGE in it.setup }?.avgMs
        return if (compute != null && vertex != null && compute > 0.0) vertex / compute else null
    }

    /** Gate C's CPU target applies in setup D: a shader pack and Fresh Animations. */
    private fun gateCSetupD(medians: List<Medians>): Boolean =
        medians.any { "+iris" in it.setup && "+fa" in it.setup }

    /**
     * `/mobium benchmark`: the median frame time of [scene] with Mobium off and on, in ms, or null while one side has
     * no run yet.
     */
    private fun benchmarkMs(scene: String): DoubleArray? {
        var off = 0
        var on = 0
        for (i in results.indices) if (results[i].scene == scene) if (resultsMobium[i]) on++ else off++
        if (off == 0 || on == 0) return null
        val offMs = DoubleArray(off)
        val onMs = DoubleArray(on)
        off = 0
        on = 0
        for (i in results.indices) {
            if (results[i].scene != scene) continue
            if (resultsMobium[i]) onMs[on++] = results[i].summary.avgMs else offMs[off++] = results[i].summary.avgMs
        }
        return doubleArrayOf(FrameStats.median(offMs), FrameStats.median(onMs))
    }

    /** What a `/mobium benchmark` result depends on besides Mobium, as `# key,value` lines of its CSV. */
    private fun appendBenchmarkLines(rows: StringBuilder) {
        for (scene in BENCHMARK_SCENES) {
            val ms = benchmarkMs(scene) ?: continue
            rows.append("# ${scene}_off_ms,${fmt(ms[0], 4)}\n")
            rows.append("# ${scene}_on_ms,${fmt(ms[1], 4)}\n")
            if (ms[1] > 0.0) rows.append("# ${scene}_speedup,${fmt(ms[0] / ms[1], 3)}\n")
        }
        val options = Minecraft.getInstance().options
        val capabilities = Capabilities.current
        val environment = environment
        rows.append("# order,off on on off\n")
        rows.append("# seconds,$seconds\n")
        rows.append("# cpu,${FrameRecorder.csv(DebugEntrySystemSpecs.getCpuInfo())}\n")
        rows.append("# gpu,${FrameRecorder.csv(capabilities?.deviceName ?: "")}\n")
        rows.append("# backend,${FrameRecorder.csv(capabilities?.backendName ?: "")}\n")
        rows.append("# display,${FrameRecorder.csv(environment?.display ?: "")}\n")
        rows.append("# resolution,${environment?.resolution ?: ""}\n")
        rows.append("# power,${environment?.power ?: ""}\n")
        rows.append("# power_profile,${FrameRecorder.csv(environment?.powerProfile ?: "")}\n")
        val shaderPack = if (Compat.isLoaded(CompatMod.IRIS) && IrisCompat.isShaderPackInUse()) IrisCompat.shaderPackName() else ""
        rows.append("# mods,${FrameRecorder.csv(Compat.describe())}\n")
        rows.append("# shader_pack,${FrameRecorder.csv(shaderPack)}\n")
        rows.append("# resource_packs,${FrameRecorder.csv(Minecraft.getInstance().resourcePackRepository.selectedIds.joinToString(" "))}\n")
        rows.append("# graphics_preset,${options.graphicsPreset().get().serializedName}\n")
        rows.append("# improved_transparency,${options.improvedTransparency().get()}\n")
        rows.append("# render_distance,${BenchOptions.RENDER_DISTANCE}\n")
        rows.append("# fov,${BenchOptions.FOV}\n")
        for (note in benchmarkNotes) rows.append("# ").append(note).append('\n')
        rows.append("# minecraft,${FrameRecorder.csv(SharedConstants.getCurrentVersion().name())}\n")
        rows.append("# mobium,${FrameRecorder.csv(Compat.modVersion(Mobium.MOD_ID) ?: "")}\n")
        rows.append("# loader,${Compat.LOADER}\n")
    }

    /** Gate A's speed-up from the medians, or null while one of the two sides has no run yet. */
    private fun gateASpeedUp(medians: List<Medians>): Double? {
        val off = medians.firstOrNull { !it.setup.endsWith("+cache") }?.avgMs
        val on = medians.firstOrNull { it.setup.endsWith("+cache") }?.avgMs
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
        if (kind == Kind.GATE_A) gateASpeedUp(medians())?.let { rows.append("# gate_a_speedup,${fmt(it, 3)}\n") }
        if (kind == Kind.GATE_B) gateB(medians())?.let { gate ->
            rows.append("# gate_b_cost_over_control,${fmt(gate.cost, 3)}\n")
            rows.append("# gate_b_nodraw_over_control,${fmt(gate.noDraw, 3)}\n")
            rows.append("# gate_b_storage_over_control,${fmt(gate.frame, 3)}\n")
            rows.append("# gate_b_drawing_ms,${fmt(gate.drawingMs, 4)}\n")
            rows.append("# gate_b_control_cache_over_vanilla,${fmt(gate.control, 3)}\n")
        }
        if (kind == Kind.GATE_C) gateC(medians())?.let { gate ->
            rows.append("# gate_c_cpu_without_over_with,${fmt(gate.cpu, 3)}\n")
            rows.append("# gate_c_frame_with_over_without,${fmt(gate.frame, 3)}\n")
        }
        if (kind == Kind.STAGES) stagesRatio(medians())?.let { rows.append("# stages_vertex_over_compute,${fmt(it, 3)}\n") }
        if (kind == Kind.BENCHMARK) appendBenchmarkLines(rows)
        rows.append("# build,${Mobium.BUILD}\n")

        return try {
            val directory = Minecraft.getInstance().gameDirectory.toPath().resolve("mobium-bench")
            Files.createDirectories(directory)
            val file = directory.resolve("${kind.filePrefix}-$stamp.csv")
            Files.writeString(file, rows)
            file
        } catch (e: IOException) {
            Mobium.LOGGER.error("Could not write the suite medians", e)
            chat(Component.translatable("mobium.bench.failed", e.message ?: e.javaClass.simpleName))
            null
        }
    }

    private fun report() {
        if (kind == Kind.BENCHMARK) {
            reportBenchmark()
            return
        }
        val medians = medians()
        for (median in medians) {
            chat(
                Component.translatable(
                    "mobium.suite.result",
                    median.scene, median.setup, fmt(median.avgMs, 2), fmt(median.avgFps, 1), fmt(median.low1Fps, 1), median.runs
                )
            )
        }

        if (kind == Kind.GATE_B) {
            gateB(medians)?.let { gate ->
                val costVerdict = if (gate.costPasses) "mobium.suite.gatea_pass" else "mobium.suite.gateb_above"
                val controlVerdict = if (gate.controlPasses) "mobium.suite.gatea_pass" else "mobium.suite.gateb_slower"
                chat(
                    Component.translatable(
                        "mobium.suite.gateb_result", fmt(gate.cost, 2), fmt(GateB.TARGET, 1),
                        Component.translatable(costVerdict), fmt(gate.control, 2), Component.translatable(controlVerdict)
                    )
                )
                chat(Component.translatable("mobium.suite.gateb_frame", fmt(gate.frame, 2), fmt(gate.drawingMs, 2)))
                Mobium.LOGGER.info(
                    "Gate B: cached objects cost {}x control (target {}x; without the draw calls' CPU time {}x), " +
                        "whole storage_room frame {}x control, drawing them {} ms, control with the cache {}x without",
                    fmt(gate.cost, 2), GateB.TARGET, fmt(gate.noDraw, 2), fmt(gate.frame, 2), fmt(gate.drawingMs, 3),
                    fmt(gate.control, 2)
                )
            }
        }
        if (kind == Kind.GATE_C) {
            gateC(medians)?.let { gate ->
                val cpuVerdict = when {
                    !gateCSetupD(medians) -> "mobium.suite.gatec_not_d"
                    gate.cpuPasses -> "mobium.suite.gatea_pass"
                    else -> "mobium.suite.gatea_below"
                }
                val frameVerdict = if (gate.framePasses) "mobium.suite.gatea_pass" else "mobium.suite.gateb_slower"
                chat(
                    Component.translatable(
                        "mobium.suite.gatec_result", fmt(gate.cpu, 2), fmt(GateC.CPU_TARGET, 1), Component.translatable(cpuVerdict),
                        fmt(gate.frame, 2), Component.translatable(frameVerdict)
                    )
                )
                Mobium.LOGGER.info(
                    "Gate C: entity CPU work {}x lower with Module 2 (target {}x in setup D), frame {}x the frame without it",
                    fmt(gate.cpu, 2), GateC.CPU_TARGET, fmt(gate.frame, 3)
                )
            }
        }
        if (kind == Kind.STAGES) {
            stagesRatio(medians)?.let { ratio ->
                chat(Component.translatable("mobium.suite.stages_result", fmt(ratio, 3)))
                Mobium.LOGGER.info("Module 2's stages: frame with the vertex stage {}x the frame with compute", fmt(ratio, 3))
            }
        }
        if (kind == Kind.GATE_A) {
            gateASpeedUp(medians)?.let { speedUp ->
                val verdict = if (speedUp >= GATE_A_TARGET) "mobium.suite.gatea_pass" else "mobium.suite.gatea_below"
                chat(Component.translatable("mobium.suite.gatea_result", fmt(speedUp, 2), fmt(GATE_A_TARGET, 1), Component.translatable(verdict)))
                Mobium.LOGGER.info("Gate A: {}x faster with the static cache (target {}x)", fmt(speedUp, 2), GATE_A_TARGET)
            }
        }

        writeMedians()?.let { chat(Component.translatable("mobium.suite.saved", it.toString())) }
    }

    /** `/mobium benchmark`'s result in player words: FPS without and with Mobium per scene, and where the file is. */
    private fun reportBenchmark() {
        for (scene in BENCHMARK_SCENES) {
            val ms = benchmarkMs(scene) ?: continue
            if (ms[0] <= 0.0 || ms[1] <= 0.0) continue
            val percent = (ms[0] / ms[1] - 1.0) * 100.0
            val verdict = when {
                kotlin.math.abs(percent) < BENCHMARK_NOISE_PERCENT -> Component.translatable("mobium.benchmark.same")
                percent > 0.0 -> Component.translatable("mobium.benchmark.faster", fmt(percent, 0))
                else -> Component.translatable("mobium.benchmark.slower", fmt(-percent, 0))
            }
            chat(
                Component.translatable(
                    "mobium.benchmark.result", Component.translatable("mobium.benchmark.scene.$scene"),
                    fmt(1000.0 / ms[0], 0), fmt(1000.0 / ms[1], 0), verdict
                )
            )
            Mobium.LOGGER.info("Benchmark {}: {} ms without Mobium, {} ms with it", scene, fmt(ms[0], 3), fmt(ms[1], 3))
        }
        val file = writeMedians() ?: return
        val folder = Component.literal(file.fileName.toString()).withStyle(ChatFormatting.UNDERLINE)
            .withStyle { it.withClickEvent(ClickEvent.OpenFile(file.parent)) }
        chat(Component.translatable("mobium.benchmark.saved", folder, link("GitHub", ISSUE_URL), link("Discord", DISCORD_URL)))
    }

    private fun link(text: String, url: String): Component = Component.literal(text).withStyle(ChatFormatting.UNDERLINE)
        .withStyle { it.withClickEvent(ClickEvent.OpenUrl(URI.create(url))) }

    private fun restoreCache() {
        config.cache.enabled = cacheConfigBefore
        config.parts.enabled = partsConfigBefore
        ModuleStates.refresh("${kind.name} finished")
    }

    private const val CACHE = "+cache"
    private const val NO_DRAW = "+cache+nodraw"
    private const val PARTS = "+parts"
    private const val VERTEX_STAGE = "+parts-vertex"

    private fun minutes(steps: Int, scenes: Int): Int {
        val perRun = seconds + FrameRecorder.WARMUP_SECONDS + 1
        return ((steps * perRun + scenes * (SETTLE_SECONDS + 5)) / 60.0).let { kotlin.math.ceil(it).toInt() }
    }

    private fun chat(message: Component) = Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(message)

    private fun fmt(value: Double, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", value)
}
