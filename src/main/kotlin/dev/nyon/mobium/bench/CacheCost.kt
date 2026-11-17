package dev.nyon.mobium.bench

/**
 * `/mobium cachecost`'s numbers (docs/benchmarks.md, *Tools*), without the GPU cost of drawing the objects, which
 * vanilla pays as well:
 *
 * - **[cost]**: what 1,000 cached chests and 300 signs cost Mobium. `storage_room` with the cache and its draw calls
 *   skipped (the objects are not drawn, so the GPU has nothing to do for them), plus the CPU time those draw calls take
 *   in the normal run, over `control` with the cache.
 * - [noDraw]: the run without draw calls over `control`, without adding their CPU time back.
 * - [frame]: the whole `storage_room` frame over `control`.
 * - [drawingMs]: what drawing the cached objects costs per frame (CPU and GPU), the difference of the two runs.
 * - **[control]**: `control` with the cache over `control` without it; the release bar allows at most
 *   [ModuleComparison.FRAME_TOLERANCE].
 *
 * Pure, so the arithmetic is unit-tested.
 */
class CacheCost(
    @JvmField val cost: Double,
    @JvmField val noDraw: Double,
    @JvmField val frame: Double,
    @JvmField val drawingMs: Double,
    @JvmField val control: Double
) {
    val controlPasses: Boolean
        get() = control <= ModuleComparison.FRAME_TOLERANCE

    /** Median frame time and median draw timer of one scene and setup, in ms. */
    class Run(@JvmField val frameMs: Double, @JvmField val drawMs: Double)

    companion object {
        /** Null while one of the four runs is missing or a frame time is not positive. */
        fun of(storage: Run?, storageNoDraw: Run?, control: Run?, baseline: Run?): CacheCost? {
            if (storage == null || storageNoDraw == null || control == null || baseline == null) return null
            val runs = arrayOf(storage, storageNoDraw, control, baseline)
            if (runs.any { it.frameMs <= 0.0 }) return null
            val drawCalls = maxOf(0.0, storage.drawMs - storageNoDraw.drawMs)
            return CacheCost(
                cost = (storageNoDraw.frameMs + drawCalls) / control.frameMs,
                noDraw = storageNoDraw.frameMs / control.frameMs,
                frame = storage.frameMs / control.frameMs,
                drawingMs = storage.frameMs - storageNoDraw.frameMs,
                control = control.frameMs / baseline.frameMs
            )
        }
    }
}
