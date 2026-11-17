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
import dev.nyon.mobium.offload.ExpandPass
import dev.nyon.mobium.offload.PartTransform
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
 * Runs benchmark plans unattended: `/mobium suite`, `compare` ([ModuleComparison]), `cachecost` ([CacheCost]), `stages`
 * and the players' `/mobium benchmark` (docs/benchmarks.md, *Tools*). A plan builds each scene through the integrated
 * server (singleplayer, no cheats needed), lets it settle for at least [SETTLE_SECONDS] and until section rebuilds,
 * light updates and server ticks have calmed down, then records; the medians are rewritten after every run, so quitting
 * halfway keeps what was measured. A plan does not start when the instance declares another setup than the active one
 * ([BenchEnvironment]). Ticked once per client tick on the render thread.
 */
object BenchSuite {
    const val DEFAULT_SECONDS = 60
    const val DEFAULT_RUNS = 3
    const val SETTLE_SECONDS = 10
    val SCENES = listOf("mob_pen_static", "mob_pen_live", "storage_room", "map_wall", "display_build", "item_drops", "control")
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
     * One benchmark run of the plan; [cache] and [offload] null leave the static cache and vertex offload as they are,
     * [stage] null leaves vertex offload's expand pass to its own choice ([ExpandPass.stage]).
     * [skipDraws] records the cached objects without drawing them (`StaticCache.skipDraws`; the setup is then named
     * `…+cache+nodraw`). [mobium] switches all of Mobium off or on through the A/B state, which then decides the
     * modules as in normal play.
     */
    private class Step(
        val scene: String,
        val cache: Boolean?,
        val skipDraws: Boolean = false,
        val offload: Boolean? = null,
        val stage: Int? = null,
        val mobium: Boolean? = null
    )

    private enum class State { IDLE, BUILDING, SETTLING, RECORDING }

    /** What a plan is for: medians only, or the numbers of one comparison. */
    private enum class Kind(val filePrefix: String) {
        SUITE("suite"), COMPARE("compare"), CACHE_COST("cachecost"), STAGES("stages"), BENCHMARK("benchmark")
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
    private var compared = Module.STATIC_CACHE
    private var cacheConfigBefore = true
    private var offloadConfigBefore = true
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

    /** The scene `/mobium compare` uses without one: where the module does the most. */
    fun compareScene(module: Module): String = if (module == Module.STATIC_CACHE) "storage_room" else "mob_pen_live"

    /** Starts `/mobium compare`: [scene] with [module] off and on in turn; returns an error message or null. */
    fun startCompare(module: Module, seconds: Int, runs: Int, scene: String): Component? {
        if (scene !in SCENES) return Component.translatable("mobium.suite.unknown_scene", scene)
        val plan = (1..runs).flatMap {
            if (module == Module.STATIC_CACHE) listOf(Step(scene, false), Step(scene, true))
            else listOf(Step(scene, null, offload = false), Step(scene, null, offload = true))
        }
        startModulePlan(plan, seconds, Kind.COMPARE, module)?.let { return it }
        compared = module
        chat(
            Component.translatable(
                "mobium.suite.compare_started", Component.translatable(module.translationKey), scene, runs, seconds,
                minutes(plan.size, 1)
            )
        )
        return null
    }

    /** Starts `/mobium cachecost`; returns an error message or null. */
    fun startCacheCost(seconds: Int, runs: Int): Component? {
        val plan = (1..runs).flatMap {
            listOf(
                Step("storage_room", true),
                Step("storage_room", true, skipDraws = true),
                Step("control", true),
                Step("control", false)
            )
        }
        startModulePlan(plan, seconds, Kind.CACHE_COST, Module.STATIC_CACHE)?.let { return it }
        chat(Component.translatable("mobium.suite.cachecost_started", runs, seconds, minutes(plan.size, plan.size / 2)))
        return null
    }

    /** Starts `/mobium stages`; returns an error message or null. */
    fun startStages(seconds: Int, runs: Int, scene: String): Component? {
        if (scene !in SCENES) return Component.translatable("mobium.suite.unknown_scene", scene)
        if (!ExpandPass.vertexStores) return Component.translatable("mobium.suite.stages_no_vertex")
        val plan = (1..runs).flatMap {
            listOf(
                Step(scene, null, offload = true, stage = ExpandPass.STAGE_COMPUTE),
                Step(scene, null, offload = true, stage = ExpandPass.STAGE_VERTEX)
            )
        }
        startModulePlan(plan, seconds, Kind.STAGES, Module.VERTEX_OFFLOAD)?.let { return it }
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
        val offload = ModuleStates.status(Module.VERTEX_OFFLOAD)
        if (!cache.active && !offload.active) {
            AbToggle.set(abOffBefore, "benchmark refused")
            return Component.translatable("mobium.benchmark.unavailable", reason(cache))
        }
        cacheConfigBefore = config.cache.enabled
        offloadConfigBefore = config.offload.enabled
        environment = BenchEnvironment.snapshot()
        // WorldOptions.parseSeed turns a seed that is not a number into its hash code.
        val seedMobium = server.overworld().seed == BENCHMARK_SEED.hashCode().toLong()
        benchmarkNotes.clear()
        benchmarkNotes += "seed_mobium,$seedMobium"
        benchmarkNotes += "module_static_cache,${cache.describe()}"
        benchmarkNotes += "module_vertex_offload,${offload.describe()}"

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
        moduleOff(offload, "mob_pen_live")
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
     * A plan that measures one module switches it itself, even if it is off in the settings (which are not saved); the
     * A/B key does not count.
     */
    private fun startModulePlan(plan: List<Step>, seconds: Int, kind: Kind, module: Module): Component? {
        cacheConfigBefore = config.cache.enabled
        offloadConfigBefore = config.offload.enabled
        if (module == Module.STATIC_CACHE) config.cache.enabled = true else config.offload.enabled = true
        ModuleStates.refresh(kind.name)
        val status = ModuleStates.status(module)
        if (!status.active && status.reason != OffReason.AB_TOGGLE) {
            restoreCache()
            return Component.translatable(
                "mobium.suite.module_unavailable", Component.translatable(module.translationKey),
                Component.translatable(status.reason!!.translationKey)
            )
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
        if (step.offload != null && step.offload != PartTransform.active) {
            PartTransform.setActive(step.offload)
            toggled = true
        }
        if (step.mobium != null) {
            val cacheBefore = StaticCache.active
            val offloadBefore = PartTransform.active
            AbToggle.set(!step.mobium, if (step.mobium) "benchmark: Mobium" else "benchmark: vanilla")
            if (StaticCache.active != cacheBefore || PartTransform.active != offloadBefore) toggled = true
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

    /** `/mobium cachecost`'s numbers from the medians, or null while one of its four runs is missing. */
    private fun cacheCost(medians: List<Medians>): CacheCost? {
        fun run(scene: String, suffix: String?): CacheCost.Run? {
            val median = medians.firstOrNull { median ->
                median.scene == scene && when (suffix) {
                    null -> !median.setup.endsWith(CACHE) && !median.setup.endsWith(NO_DRAW)
                    else -> median.setup.endsWith(suffix)
                }
            } ?: return null
            return CacheCost.Run(median.avgMs, median.drawMs)
        }
        return CacheCost.of(run("storage_room", CACHE), run("storage_room", NO_DRAW), run("control", CACHE), run("control", null))
    }

    /** `/mobium compare`'s numbers from the medians, or null while one of the two sides has no run yet. */
    private fun comparison(medians: List<Medians>): ModuleComparison? {
        fun run(on: Boolean): ModuleComparison.Run? {
            val median = medians.firstOrNull {
                val withModule = if (compared == Module.STATIC_CACHE) it.setup.endsWith(CACHE) else OFFLOAD in it.setup
                withModule == on
            } ?: return null
            return ModuleComparison.Run(median.avgMs, median.prepareMs, median.shadowPrepareMs)
        }
        return ModuleComparison.of(run(false), run(true))
    }

    /** `/mobium stages`: the vertex stage's median frame over compute's, or null while one side has no run yet. */
    private fun stagesRatio(medians: List<Medians>): Double? {
        val compute = medians.firstOrNull { OFFLOAD in it.setup && VERTEX_STAGE !in it.setup }?.avgMs
        val vertex = medians.firstOrNull { VERTEX_STAGE in it.setup }?.avgMs
        return if (compute != null && vertex != null && compute > 0.0) vertex / compute else null
    }

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
        rows.append("# os,${FrameRecorder.csv(environment?.os ?: "")}\n")
        rows.append("# cpu,${FrameRecorder.csv(DebugEntrySystemSpecs.getCpuInfo())}\n")
        rows.append("# gpu,${FrameRecorder.csv(capabilities?.deviceName ?: "")}\n")
        rows.append("# driver,${FrameRecorder.csv(environment?.driver ?: "")}\n")
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

    /** Rewrites `<kind>-<stamp>.csv` (`suite-…`, `compare-…`) with the medians of the runs so far; null on failure. */
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
        if (kind == Kind.COMPARE) {
            rows.append("# compare_module,${compared.key}\n")
            comparison(medians())?.let { comparison ->
                rows.append("# compare_frame_with_over_without,${fmt(comparison.frame, 3)}\n")
                if (!comparison.cpu.isNaN()) rows.append("# compare_cpu_without_over_with,${fmt(comparison.cpu, 3)}\n")
            }
        }
        if (kind == Kind.CACHE_COST) cacheCost(medians())?.let { cost ->
            rows.append("# cachecost_cost_over_control,${fmt(cost.cost, 3)}\n")
            rows.append("# cachecost_nodraw_over_control,${fmt(cost.noDraw, 3)}\n")
            rows.append("# cachecost_storage_over_control,${fmt(cost.frame, 3)}\n")
            rows.append("# cachecost_drawing_ms,${fmt(cost.drawingMs, 4)}\n")
            rows.append("# cachecost_control_cache_over_vanilla,${fmt(cost.control, 3)}\n")
        }
        if (kind == Kind.STAGES) stagesRatio(medians())?.let { rows.append("# stages_vertex_over_compute,${fmt(it, 3)}\n") }
        if (kind == Kind.BENCHMARK) appendBenchmarkLines(rows)
        rows.append("# throttled_runs,${throttledRuns()}\n")
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

    /** Runs with frames that vanilla capped itself (window minimized or not focused); their numbers do not count. */
    private fun throttledRuns(): Int {
        var count = 0
        for (result in results) if (result.throttledFrames > 0) count++
        return count
    }

    private fun warnThrottled() {
        val throttled = throttledRuns()
        if (throttled == 0) return
        chat(Component.translatable("mobium.suite.throttled", throttled, results.size))
        Mobium.LOGGER.warn(
            "{} of {} runs ran under the game's own frame cap (window minimized or not focused); medians include them",
            throttled, results.size
        )
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
        warnThrottled()

        if (kind == Kind.CACHE_COST) {
            cacheCost(medians)?.let { cost ->
                val verdict = if (cost.controlPasses) "mobium.suite.compare_pass" else "mobium.suite.compare_slower"
                chat(
                    Component.translatable(
                        "mobium.suite.cachecost_result", fmt(cost.cost, 2), fmt(cost.control, 2),
                        fmt(ModuleComparison.FRAME_TOLERANCE, 2), Component.translatable(verdict)
                    )
                )
                chat(Component.translatable("mobium.suite.cachecost_frame", fmt(cost.frame, 2), fmt(cost.drawingMs, 2)))
                Mobium.LOGGER.info(
                    "Cache cost: cached objects cost {}x control (without the draw calls' CPU time {}x), " +
                        "whole storage_room frame {}x control, drawing them {} ms, control with the cache {}x without",
                    fmt(cost.cost, 2), fmt(cost.noDraw, 2), fmt(cost.frame, 2), fmt(cost.drawingMs, 3), fmt(cost.control, 2)
                )
            }
        }
        if (kind == Kind.COMPARE) {
            comparison(medians)?.let { comparison ->
                val scene = medians[0].scene
                val module = Component.translatable(compared.translationKey)
                val verdict = if (comparison.framePasses) "mobium.suite.compare_pass" else "mobium.suite.compare_slower"
                chat(
                    Component.translatable(
                        "mobium.suite.compare_result", module, scene, fmt(comparison.frame, 2),
                        fmt(ModuleComparison.FRAME_TOLERANCE, 2), Component.translatable(verdict)
                    )
                )
                if (compared == Module.VERTEX_OFFLOAD && !comparison.cpu.isNaN()) {
                    chat(Component.translatable("mobium.suite.compare_cpu", fmt(comparison.cpu, 2)))
                }
                Mobium.LOGGER.info(
                    "Compare {} in {}: frame {}x the frame without it, entity CPU work {}x lower with it",
                    compared.key, scene, fmt(comparison.frame, 3), fmt(comparison.cpu, 2)
                )
            }
        }
        if (kind == Kind.STAGES) {
            stagesRatio(medians)?.let { ratio ->
                chat(Component.translatable("mobium.suite.stages_result", fmt(ratio, 3)))
                Mobium.LOGGER.info("Vertex offload's stages: frame with the vertex stage {}x the frame with compute", fmt(ratio, 3))
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
        warnThrottled()
        val file = writeMedians() ?: return
        val folder = Component.literal(file.fileName.toString()).withStyle(ChatFormatting.UNDERLINE)
            .withStyle { it.withClickEvent(ClickEvent.OpenFile(file.parent)) }
        chat(Component.translatable("mobium.benchmark.saved", folder, link("GitHub", ISSUE_URL), link("Discord", DISCORD_URL)))
    }

    private fun link(text: String, url: String): Component = Component.literal(text).withStyle(ChatFormatting.UNDERLINE)
        .withStyle { it.withClickEvent(ClickEvent.OpenUrl(URI.create(url))) }

    private fun restoreCache() {
        config.cache.enabled = cacheConfigBefore
        config.offload.enabled = offloadConfigBefore
        ModuleStates.refresh("${kind.name} finished")
    }

    private const val CACHE = "+cache"
    private const val NO_DRAW = "+cache+nodraw"
    private const val OFFLOAD = "+offload"
    private const val VERTEX_STAGE = "+offload-vertex"

    private fun minutes(steps: Int, scenes: Int): Int {
        val perRun = seconds + FrameRecorder.WARMUP_SECONDS + 1
        return ((steps * perRun + scenes * (SETTLE_SECONDS + 5)) / 60.0).let { kotlin.math.ceil(it).toInt() }
    }

    private fun chat(message: Component) = Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(message)

    private fun fmt(value: Double, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", value)
}
