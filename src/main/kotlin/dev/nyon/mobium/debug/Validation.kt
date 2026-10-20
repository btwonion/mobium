package dev.nyon.mobium.debug

import dev.nyon.mobium.core.Compat

/**
 * Validation mode (PLAN.md §5.3, dev builds only): draw a frame both ways, Mobium's and vanilla's, off-screen and
 * compare the pictures pixel by pixel, so a difference shows up as a number instead of relying on someone looking.
 * M5 needs it for the "zero screenshot differences" of Gate B.
 *
 * Skeleton for M4: this is where the switch lives and what the frame comparison will report. The off-screen second
 * pass needs a render target and a way to run the level's feature draws twice per frame; until M5 builds that, the
 * Fabric gametests compare whole screenshots with the modules off and on (`ScreenshotDiff`), which is the same check
 * one frame at a time.
 */
object Validation {
    /** Only development environments (dev clients, gametests) can enable it. */
    val available: Boolean
        get() = Compat.isDevelopment

    /** Requested by `/mobium debug validate`; not acted on until M5. */
    var requested = false
        private set

    /** Result of one comparison: how many pixels differ and by how much at most (0–255 per channel). */
    data class FrameDiff(val pixels: Int, val differing: Int, val maxChannelDifference: Int) {
        val identical: Boolean
            get() = differing == 0
    }

    fun request(): Boolean {
        if (!available) return false
        requested = true
        return true
    }
}
