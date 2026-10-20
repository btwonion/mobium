package dev.nyon.mobium.bench

/**
 * Gate B's numbers (PLAN.md M5), as Anton defined the gate on 20 Oct 2026 after the first measurement failed on the GPU
 * cost of drawing the objects, which vanilla pays as well:
 *
 * - **[cost]**, the gate: what 1,000 cached chests and 300 signs cost Mobium. `storage_room` with the cache and its
 *   draw calls skipped (the objects are not drawn, so the GPU has nothing to do for them), plus the CPU time those
 *   draw calls take in the normal run, over `control` with the cache. Target at most [TARGET].
 * - [noDraw]: the run without draw calls over `control`, without adding their CPU time back; reported, no target.
 * - [frame]: the whole `storage_room` frame over `control`, the gate's first definition; reported, no target.
 * - [drawingMs]: what drawing the cached objects costs per frame (CPU and GPU), the difference of the two runs.
 * - **[control]**: `control` with the cache over `control` without it; must not be slower, up to [CONTROL_TOLERANCE].
 *
 * Pure, so the arithmetic is unit-tested.
 */
class GateB(
    @JvmField val cost: Double,
    @JvmField val noDraw: Double,
    @JvmField val frame: Double,
    @JvmField val drawingMs: Double,
    @JvmField val control: Double
) {
    val costPasses: Boolean
        get() = cost <= TARGET

    val controlPasses: Boolean
        get() = control <= CONTROL_TOLERANCE

    /** Median frame time and median draw timer of one scene and setup, in ms. */
    class Run(@JvmField val frameMs: Double, @JvmField val drawMs: Double)

    companion object {
        const val TARGET = 1.2

        /** `control` with the cache may be this much slower than without it and still count as not slower (noise). */
        const val CONTROL_TOLERANCE = 1.03

        /** Null while one of the four runs is missing or a frame time is not positive. */
        fun of(storage: Run?, storageNoDraw: Run?, control: Run?, baseline: Run?): GateB? {
            if (storage == null || storageNoDraw == null || control == null || baseline == null) return null
            if (storage.frameMs <= 0.0 || storageNoDraw.frameMs <= 0.0 || control.frameMs <= 0.0 || baseline.frameMs <= 0.0) {
                return null
            }
            val drawCalls = maxOf(0.0, storage.drawMs - storageNoDraw.drawMs)
            return GateB(
                cost = (storageNoDraw.frameMs + drawCalls) / control.frameMs,
                noDraw = storageNoDraw.frameMs / control.frameMs,
                frame = storage.frameMs / control.frameMs,
                drawingMs = storage.frameMs - storageNoDraw.frameMs,
                control = control.frameMs / baseline.frameMs
            )
        }
    }
}
