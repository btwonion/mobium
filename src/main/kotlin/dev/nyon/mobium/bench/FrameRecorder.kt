package dev.nyon.mobium.bench

import dev.nyon.mobium.Mobium
import dev.nyon.mobium.core.Capabilities
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.CompatMod
import dev.nyon.mobium.core.IrisCompat
import dev.nyon.mobium.debug.Sections
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Records frame times and [Sections] timers for `/mobium bench`, then writes
 * `<game dir>/mobium-bench/<scene>-<setup>-<timestamp>.csv`, appends one row to `mobium-bench/summary.csv` and prints a
 * summary in chat. A run is [WARMUP_SECONDS] of warm-up followed by the requested number of seconds; the HUD is hidden
 * while it runs. [onFrameStart] is called once per game-loop iteration on the render thread and does not allocate
 * unless a run outgrows its preallocated arrays.
 */
object FrameRecorder {
    const val WARMUP_SECONDS = 5
    private const val PREALLOCATED_FPS = 400

    private enum class Phase { IDLE, WARMUP, RECORDING }

    private var phase = Phase.IDLE
    private var scene = ""
    private var setup = ""
    private var seconds = 0
    private var phaseEndNanos = 0L
    private var lastFrameStart = 0L
    private var hudToggled = false

    private var frames = 0
    private var frameNanos = LongArray(0)
    private var sectionNanos = LongArray(0)
    private var entityCounts = IntArray(0)
    private var blockEntityCounts = IntArray(0)

    val isActive: Boolean
        get() = phase != Phase.IDLE

    @JvmStatic
    fun onFrameStart(now: Long) {
        Sections.endFrame(phase != Phase.IDLE)
        val previous = lastFrameStart
        lastFrameStart = now
        if (phase == Phase.IDLE || previous == 0L) return

        if (phase == Phase.WARMUP) {
            if (now >= phaseEndNanos) {
                phase = Phase.RECORDING
                phaseEndNanos = now + seconds * NANOS_PER_SECOND
            }
            return
        }

        record(now - previous)
        if (now >= phaseEndNanos) finish()
    }

    /** Starts a run; returns false if one is already running. */
    fun start(scene: String, seconds: Int, setup: String?): Boolean {
        if (isActive) return false
        this.scene = sanitize(scene)
        this.setup = sanitize(setup ?: detectSetup())
        this.seconds = seconds

        val capacity = seconds * PREALLOCATED_FPS + 256
        frames = 0
        frameNanos = LongArray(capacity)
        sectionNanos = LongArray(capacity * Sections.COUNT)
        entityCounts = IntArray(capacity)
        blockEntityCounts = IntArray(capacity)

        phase = Phase.WARMUP
        phaseEndNanos = System.nanoTime() + WARMUP_SECONDS * NANOS_PER_SECOND
        val hud = Minecraft.getInstance().gui.hud
        hudToggled = !hud.isHidden
        if (hudToggled) hud.toggle()

        chat(Component.translatable("mobium.bench.started", this.scene, this.setup, WARMUP_SECONDS, seconds))
        return true
    }

    /** Aborts a running benchmark without writing results. */
    fun stop(): Boolean {
        if (!isActive) return false
        phase = Phase.IDLE
        restoreHud()
        chat(Component.translatable("mobium.bench.stopped"))
        return true
    }

    private fun record(frameTime: Long) {
        if (frames == frameNanos.size) grow()
        frameNanos[frames] = frameTime
        System.arraycopy(Sections.lastFrame, 0, sectionNanos, frames * Sections.COUNT, Sections.COUNT)
        entityCounts[frames] = Sections.lastEntities
        blockEntityCounts[frames] = Sections.lastBlockEntities
        frames++
    }

    private fun grow() {
        val capacity = frameNanos.size * 2
        frameNanos = frameNanos.copyOf(capacity)
        sectionNanos = sectionNanos.copyOf(capacity * Sections.COUNT)
        entityCounts = entityCounts.copyOf(capacity)
        blockEntityCounts = blockEntityCounts.copyOf(capacity)
    }

    private fun finish() {
        phase = Phase.IDLE
        restoreHud()
        if (frames == 0) {
            chat(Component.translatable("mobium.bench.empty"))
            return
        }

        val summary = FrameStats.summarize(frameNanos, frames)
        val sectionMeans = DoubleArray(Sections.COUNT) { FrameStats.columnMeanMs(sectionNanos, frames, Sections.COUNT, it) }
        val entities = entityCounts.copyOf(frames).average().toInt()
        val blockEntities = blockEntityCounts.copyOf(frames).average().toInt()

        chat(
            Component.translatable(
                "mobium.bench.finished",
                scene,
                setup,
                summary.frames,
                fmt(summary.avgFps, 1),
                fmt(summary.low1Fps, 1),
                fmt(summary.avgMs, 2),
                fmt(summary.p95Ms, 2)
            )
        )
        chat(
            Component.translatable(
                "mobium.bench.sections",
                fmt(sectionMeans[Sections.EXTRACT_ENTITIES] + sectionMeans[Sections.EXTRACT_BLOCK_ENTITIES], 2),
                fmt(sectionMeans[Sections.SUBMIT_ENTITIES] + sectionMeans[Sections.SUBMIT_BLOCK_ENTITIES], 2),
                fmt(sectionMeans[Sections.POSE_ENTITIES] + sectionMeans[Sections.POSE_BLOCK_ENTITIES], 2),
                fmt(sectionMeans[Sections.TESSELLATE_ENTITIES] + sectionMeans[Sections.TESSELLATE_BLOCK_ENTITIES], 2),
                fmt(sectionMeans[Sections.PREPARE], 2),
                fmt(sectionMeans[Sections.DRAW], 2)
            )
        )

        try {
            val directory = Minecraft.getInstance().gameDirectory.toPath().resolve("mobium-bench")
            Files.createDirectories(directory)
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val file = directory.resolve("$scene-$setup-$stamp.csv")
            writeFrames(file)
            appendSummary(directory.resolve("summary.csv"), stamp, summary, sectionMeans, entities, blockEntities)
            chat(Component.translatable("mobium.bench.saved", file.toString()))
            Mobium.LOGGER.info(
                "Benchmark {} ({}): {} frames, avg {} FPS, 1% low {} FPS, avg {} ms, p95 {} ms -> {}",
                scene, setup, summary.frames, fmt(summary.avgFps, 1), fmt(summary.low1Fps, 1),
                fmt(summary.avgMs, 2), fmt(summary.p95Ms, 2), file
            )
        } catch (e: IOException) {
            Mobium.LOGGER.error("Could not write benchmark results", e)
            chat(Component.translatable("mobium.bench.failed", e.message ?: e.javaClass.simpleName))
        }
    }

    private fun writeFrames(file: Path) {
        Files.newBufferedWriter(file).use { out ->
            out.write("frame,frame_ms")
            for (name in Sections.NAMES) out.write(",${name}_ms")
            out.write(",entities,block_entities\n")
            for (frame in 0 until frames) {
                out.write(frame.toString())
                out.write(",")
                out.write(fmt(frameNanos[frame] / FrameStats.NANOS_PER_MS, 4))
                for (section in 0 until Sections.COUNT) {
                    out.write(",")
                    out.write(fmt(sectionNanos[frame * Sections.COUNT + section] / FrameStats.NANOS_PER_MS, 4))
                }
                out.write(",${entityCounts[frame]},${blockEntityCounts[frame]}\n")
            }
        }
    }

    private fun appendSummary(
        file: Path,
        stamp: String,
        summary: FrameSummary,
        sectionMeans: DoubleArray,
        entities: Int,
        blockEntities: Int
    ) {
        val header = buildString {
            append("timestamp,scene,setup,seconds,frames,avg_fps,low1_fps,avg_ms,p95_ms")
            for (name in Sections.NAMES) append(",${name}_ms")
            append(",entities,block_entities,minecraft,mobium,loader,backend,gpu\n")
        }
        val capabilities = Capabilities.current
        val row = buildString {
            append("$stamp,$scene,$setup,$seconds,${summary.frames},${fmt(summary.avgFps, 2)},${fmt(summary.low1Fps, 2)}")
            append(",${fmt(summary.avgMs, 4)},${fmt(summary.p95Ms, 4)}")
            for (mean in sectionMeans) append(",${fmt(mean, 4)}")
            append(",$entities,$blockEntities")
            append(",${csv(SharedConstants.getCurrentVersion().name())}")
            append(",${csv(Compat.modVersion(Mobium.MOD_ID) ?: "")}")
            append(",${Compat.LOADER}")
            append(",${csv(capabilities?.backendName ?: "")}")
            append(",${csv(capabilities?.deviceName ?: "")}\n")
        }
        if (Files.notExists(file)) Files.writeString(file, header)
        Files.writeString(file, row, StandardOpenOption.APPEND)
    }

    /** The setup name for the file: base renderer, Iris with an active pack, EMF, and Fresh Animations if selected. */
    private fun detectSetup(): String {
        val parts = ArrayList<String>()
        parts += if (Compat.isLoaded(CompatMod.SODIUM)) "sodium" else "vanilla"
        if (Compat.isLoaded(CompatMod.IRIS) && IrisCompat.isShaderPackInUse()) parts += "iris"
        if (Compat.isLoaded(CompatMod.EMF)) parts += "emf"
        val packs = Minecraft.getInstance().resourcePackRepository.selectedIds
        if (packs.any { "fresh" in it.lowercase(Locale.ROOT) }) parts += "fa"
        return parts.joinToString("+")
    }

    private fun restoreHud() {
        if (hudToggled) {
            val hud = Minecraft.getInstance().gui.hud
            if (hud.isHidden) hud.toggle()
            hudToggled = false
        }
    }

    private fun chat(message: Component) = Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(message)

    private fun sanitize(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_+.-]"), "_")

    private fun fmt(value: Double, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", value)

    private fun csv(value: String) = if (value.any { it == ',' || it == '"' || it == '\n' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else value

    private const val NANOS_PER_SECOND = 1_000_000_000L
}
