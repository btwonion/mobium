package dev.nyon.mobium.bench

/**
 * `/mobium compare`'s numbers: one scene with a module off and on in one session (docs/benchmarks.md, *Tools*).
 *
 * - **[frame]**: the frame time with the module over without it. The release bar (docs/releasing.md) allows at most
 *   [FRAME_TOLERANCE].
 * - [cpu]: the entity CPU work of the frame, `prepare` + `shadow_prepare` (posing, tessellation and upload of the
 *   level's and Iris's shadow pass's prepare stages), without the module over with it; what vertex offload takes away.
 *   NaN when the run with the module has no such work.
 *
 * Pure, so the arithmetic is unit-tested.
 */
class ModuleComparison(@JvmField val frame: Double, @JvmField val cpu: Double) {
    val framePasses: Boolean
        get() = frame <= FRAME_TOLERANCE

    /** Median frame time and median `prepare` and `shadow_prepare` timers of one setup, in ms. */
    class Run(@JvmField val frameMs: Double, @JvmField val prepareMs: Double, @JvmField val shadowPrepareMs: Double)

    companion object {
        /** A frame with the module this much slower than without it still counts as not slower (noise). */
        const val FRAME_TOLERANCE = 1.03

        /** Null while one of the two sides is missing or a frame time is not positive. */
        fun of(without: Run?, with: Run?): ModuleComparison? {
            if (without == null || with == null || without.frameMs <= 0.0 || with.frameMs <= 0.0) return null
            val cpuWith = with.prepareMs + with.shadowPrepareMs
            return ModuleComparison(
                frame = with.frameMs / without.frameMs,
                cpu = if (cpuWith > 0.0) (without.prepareMs + without.shadowPrepareMs) / cpuWith else Double.NaN
            )
        }
    }
}
