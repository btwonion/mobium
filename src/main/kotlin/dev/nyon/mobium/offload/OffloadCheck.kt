package dev.nyon.mobium.offload

import dev.nyon.mobium.Mobium
import dev.nyon.mobium.core.Capabilities
import dev.nyon.mobium.core.Fallback
import dev.nyon.mobium.core.Module
import java.util.Locale

/**
 * Vertex offload's check on the player's GPU. `expand.comp` is written to give exactly the CPU's bytes, but the dev
 * validation runs on a few GPUs only (`docs/compat.md`); another driver may round or fuse operations differently. So
 * for each path through the shader ([path]: the part's flags, the cube layout, the stage, the arithmetic) the first
 * [PER_PATH] parts, at most [PER_FRAME] per frame, are also compiled on the CPU and compared after the expand pass
 * ([OffloadValidation], which waits for the GPU once per staged buffer in those frames). On a difference the compared
 * parts get the CPU's bytes in that frame (the frame's other parts keep the GPU's, for that one frame) and vertex
 * offload fails: one toast, vanilla until restart, the log names the GPU and the first differing vertices. Render
 * thread only.
 */
object OffloadCheck {
    private const val PATHS = 4096
    private const val PER_PATH = 64
    private const val PER_FRAME = 8
    private const val FLAG_BITS = 0x1FF
    private const val SODIUM_LAYOUT = 1 shl 9
    private const val STAGE_SHIFT = 10
    private const val ARITHMETIC_SHIFT = 11

    private val sampler = PathSampler(PATHS, PER_PATH, PER_FRAME)

    /** Parts compared with the CPU and the paths they came from, since the start or the last [forceDifference]. */
    val checkedParts: Int
        get() = sampler.total
    val checkedPaths: Int
        get() = sampler.pathsSeen

    /** Comparisons that found a difference since the start (vertex offload is off after the first). */
    var failures = 0
        private set

    /** Dev only (`/mobium debug gpucheck`): the next comparison finds one changed byte in the GPU's vertices. */
    @JvmField
    var forcedDifference = false

    /**
     * The path of a part or an item run with [flags] (`OffloadBatch` part flags, 9 bits), cubes in Sodium's layout or
     * not, expanded in [stage] with [arithmetic] (`ExpandPass.ARITHMETIC_*`); -1 (never sampled) before the first
     * expand pass has picked a stage.
     */
    fun path(flags: Int, sodiumLayout: Boolean, stage: Int, arithmetic: Int): Int {
        if (stage < 0) return -1
        return (flags and FLAG_BITS) or (if (sodiumLayout) SODIUM_LAYOUT else 0) or (stage shl STAGE_SHIFT) or
            (arithmetic shl ARITHMETIC_SHIFT)
    }

    /** Whether to compare the next part of [path] with the CPU; per part, allocates nothing. */
    fun sample(path: Int): Boolean = sampler.take(path)

    fun endFrame() = sampler.endFrame()

    /** Dev only: every path is sampled again, and the next comparison finds a difference. */
    fun forceDifference() {
        sampler.reset()
        forcedDifference = true
    }

    /**
     * A comparison found [vertices] differing vertices and [parts] parts the CPU wrote otherwise: vertex offload fails.
     */
    fun failed(vertices: Long, parts: Int, stage: String) {
        failures++
        val capabilities = Capabilities.current
        val detail = "differing: $vertices vertices, $parts parts; $stage"
        Mobium.LOGGER.error(
            "Mobium vertex_offload: the GPU's vertices differ from the CPU's on {} ({}, {}): {}; first differences: {}",
            capabilities?.deviceName, capabilities?.vendor, capabilities?.driverInfo, detail, OffloadValidation.reports
        )
        val error = IllegalStateException("the GPU's vertices differ from the CPU's ($detail)")
        Fallback.moduleFailed(Module.VERTEX_OFFLOAD, error)
    }

    /** F3 `mobium:modules`: how much of the check has run and what it found. */
    fun debugLine(): String = String.format(
        Locale.ROOT, "Mobium GPU check: %d parts in %d paths compared, %s",
        checkedParts, checkedPaths, if (failures == 0) "no difference" else "differences found"
    )
}
