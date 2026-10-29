package dev.nyon.mobium.debug

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.commands.GpuQueryPool

/**
 * Dev measurement for Module 2's fixed cost per frame (self-check `--mode expand-cost`): CPU time of each step of
 * `StagedVertexBuffer.upload()` and the expand pass, summed over all staged buffers until [reset]; the GPU time of
 * each frame from timestamps at its start (`runTick`) and right before its submit; and the GPU time of each expand
 * pass from timestamps right before it and after its barrier, which for a compute dispatch on a driver that records it
 * in a batch of its own includes the hand-over between the batches. Timestamps are Renderpearl's
 * (`GpuDevice.createTimestampQueryPool`, `CommandEncoder.writeTimestamp`: `glQueryCounter` on OpenGL,
 * `vkCmdWriteTimestamp2` on Vulkan, whose ticks `DeviceInfo.timestampPeriod` turns into nanoseconds; §21) and are read
 * [FRAMES_IN_FLIGHT] frames later, so that nothing waits for the GPU. Only runs while [enabled]; otherwise the callers
 * skip it. Reading a result allocates (Renderpearl returns `OptionalLong`s), a few objects per frame while measuring.
 * Render thread only.
 */
object ExpandProfile {
    /** Vanilla's `upload()` of every staged buffer, Module 2's copy without the reserved bytes included. */
    const val UPLOAD = 0

    /** New meshes and the batch's records into their storage buffers. */
    const val RECORDS = 1

    /** Program, storage buffer bindings and uniforms. */
    const val STATE = 2

    /** The dispatch, or the point draw of the vertex-stage variant. */
    const val RUN = 3

    /** The vertex-attribute barrier, unbinding and the previous program. */
    const val BARRIER = 4
    const val COUNT = 5

    @JvmField
    val NAMES = arrayOf("upload", "records", "state", "run", "barrier")

    @JvmField
    var enabled = false

    @JvmField
    val nanos = LongArray(COUNT)

    /** Expand passes run since [reset]. */
    @JvmField
    var passes = 0

    /** Start of the `upload()` in progress. */
    @JvmField
    var uploadStart = 0L

    /** GPU time of the frames read back since [reset], and their number. */
    @JvmField
    var gpuNanos = 0L

    @JvmField
    var gpuFrames = 0

    /** GPU time of the expand passes read back since [reset], and the number of frames they were read from. */
    @JvmField
    var passGpuNanos = 0L

    @JvmField
    var passGpuFrames = 0

    private const val FRAMES_IN_FLIGHT = 4
    private const val MAX_PASSES = 4
    private var queries: GpuQueryPool? = null
    private var passQueries: GpuQueryPool? = null
    private var nanosPerTick = 1.0
    private val pending = BooleanArray(FRAMES_IN_FLIGHT)
    private val passesInFrame = IntArray(FRAMES_IN_FLIGHT)
    private var slot = 0
    private var frameOpen = false

    /** Adds the time since [start] to [step] and returns now, the next step's start. */
    @JvmStatic
    fun lap(step: Int, start: Long): Long {
        val now = System.nanoTime()
        nanos[step] += now - start
        return now
    }

    /** Called at the start of each frame (`Minecraft.runTick`). */
    @JvmStatic
    fun frameStart() {
        if (!enabled) return
        val device = RenderSystem.getDevice()
        val queries = queries ?: device.createTimestampQueryPool(FRAMES_IN_FLIGHT * 2).also {
            queries = it
            nanosPerTick = device.deviceInfo.timestampPeriod().toDouble()
        }
        val passQueries = passQueries ?: device.createTimestampQueryPool(FRAMES_IN_FLIGHT * MAX_PASSES * 2).also { passQueries = it }
        slot = (slot + 1) % FRAMES_IN_FLIGHT
        if (pending[slot]) read(queries, passQueries, slot)
        passesInFrame[slot] = 0
        device.createCommandEncoder().writeTimestamp(queries, slot * 2)
        frameOpen = true
    }

    /** Right before an expand pass's first GL command that the GPU runs. */
    @JvmStatic
    fun passStart() {
        val passQueries = passQueries ?: return
        if (!frameOpen || passesInFrame[slot] == MAX_PASSES) return
        RenderSystem.getDevice().createCommandEncoder().writeTimestamp(passQueries, (slot * MAX_PASSES + passesInFrame[slot]) * 2)
    }

    /** Right after an expand pass's barrier. */
    @JvmStatic
    fun passEnd() {
        val passQueries = passQueries ?: return
        if (!frameOpen || passesInFrame[slot] == MAX_PASSES) return
        RenderSystem.getDevice().createCommandEncoder().writeTimestamp(passQueries, (slot * MAX_PASSES + passesInFrame[slot]) * 2 + 1)
        passesInFrame[slot]++
    }

    /** Called right before the frame's commands are submitted (`Minecraft.renderFrame`). */
    @JvmStatic
    fun frameEnd() {
        if (!frameOpen) return
        frameOpen = false
        val queries = queries ?: return
        RenderSystem.getDevice().createCommandEncoder().writeTimestamp(queries, slot * 2 + 1)
        pending[slot] = true
    }

    private fun read(queries: GpuQueryPool, passQueries: GpuQueryPool, slot: Int) {
        pending[slot] = false
        // The frame's end comes after its passes: once it is available, so are they.
        val end = queries.getValue(slot * 2 + 1)
        val start = queries.getValue(slot * 2)
        if (end.isEmpty || start.isEmpty) return
        gpuNanos += toNanos(end.asLong - start.asLong)
        gpuFrames++
        for (pass in 0 until passesInFrame[slot]) {
            val index = (slot * MAX_PASSES + pass) * 2
            val passStart = passQueries.getValue(index)
            val passEnd = passQueries.getValue(index + 1)
            if (passStart.isPresent && passEnd.isPresent) passGpuNanos += toNanos(passEnd.asLong - passStart.asLong)
        }
        passGpuFrames++
    }

    private fun toNanos(ticks: Long): Long = if (nanosPerTick == 1.0) ticks else (ticks * nanosPerTick).toLong()

    fun reset() {
        nanos.fill(0L)
        passes = 0
        gpuNanos = 0L
        gpuFrames = 0
        passGpuNanos = 0L
        passGpuFrames = 0
        pending.fill(false)
        frameOpen = false
    }
}
