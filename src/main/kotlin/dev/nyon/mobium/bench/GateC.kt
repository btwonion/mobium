package dev.nyon.mobium.bench

/**
 * Gate C's numbers (docs/benchmarks.md, *Gates*), as Anton redefined the gate on 25 Oct 2026, from `mob_pen_live` with
 * Module 2 off and on in one session:
 *
 * - **[cpu]**: the entity CPU work of the frame, `prepare` + `shadow_prepare` (posing, tessellation and upload of the
 *   level's and Iris's shadow pass's prepare stages), without Module 2 over with it. Target at least [CPU_TARGET] in
 *   setup D (shader pack and Fresh Animations); reported in the other setups.
 * - **[frame]**: the frame time with Module 2 over without it; must not be slower, up to [FRAME_TOLERANCE], in C and D.
 *
 * Pure, so the arithmetic is unit-tested.
 */
class GateC(@JvmField val cpu: Double, @JvmField val frame: Double) {
    val cpuPasses: Boolean
        get() = cpu >= CPU_TARGET

    val framePasses: Boolean
        get() = frame <= FRAME_TOLERANCE

    /** Median frame time and median `prepare` and `shadow_prepare` timers of one setup, in ms. */
    class Run(@JvmField val frameMs: Double, @JvmField val prepareMs: Double, @JvmField val shadowPrepareMs: Double)

    companion object {
        const val CPU_TARGET = 1.5

        /** The same noise allowance as Gate B's `control` check. */
        const val FRAME_TOLERANCE = 1.03

        /** Null while one of the two sides is missing or a number is not positive. */
        fun of(without: Run?, with: Run?): GateC? {
            if (without == null || with == null || without.frameMs <= 0.0 || with.frameMs <= 0.0) return null
            val cpuWith = with.prepareMs + with.shadowPrepareMs
            if (cpuWith <= 0.0) return null
            return GateC(
                cpu = (without.prepareMs + without.shadowPrepareMs) / cpuWith,
                frame = with.frameMs / without.frameMs
            )
        }
    }
}
