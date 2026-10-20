package dev.nyon.mobium.debug

import com.mojang.blaze3d.platform.NativeImage
import dev.nyon.mobium.core.Compat
import kotlin.math.abs
import kotlin.math.max

/**
 * Validation mode (PLAN.md §5.3, dev builds only): draw a frame both ways, Mobium's and vanilla's, off-screen and
 * compare the pictures pixel by pixel, so a difference shows up as a number instead of relying on someone looking.
 * M5 needs it for the "zero screenshot differences" of Gate B.
 *
 * Skeleton for M4: the switch and the pixel comparison ([compare]) exist. The off-screen second pass needs a render
 * target and a way to run the level's feature draws twice per frame; until M5 builds that, the Fabric gametests
 * (`ScreenshotDiff`) and the dev self-check compare whole frames with the modules off and on, by the same rule.
 */
object Validation {
    /** Only development environments (dev clients, gametests) can enable it. */
    val available: Boolean
        get() = Compat.isDevelopment

    /** Requested by `/mobium debug validate`; not acted on until M5. */
    var requested = false
        private set

    /** A pixel differs when a colour channel differs by more than this (out of 255), as in the gametests. */
    const val CHANNEL_THRESHOLD = 16

    /** Share of differing pixels a comparison may have, as in the gametests (antialiasing, cloud drift). */
    const val MAX_DIFFERING_FRACTION = 0.001

    /**
     * Result of one comparison: how many pixels differ by more than [CHANNEL_THRESHOLD], how many differ at all
     * ([changed]) and by how much at most (0–255 per channel). The threshold hides small systematic errors (the M3
     * prototype's fog was off by up to 6 levels and passed), so [identical] is the stricter test.
     */
    data class FrameDiff(val pixels: Int, val differing: Int, val maxChannelDifference: Int, val changed: Int = differing) {
        val passes: Boolean
            get() = pixels > 0 && differing <= pixels * MAX_DIFFERING_FRACTION

        /**
         * Almost no pixel changed at all: only isolated edge pixels may flip (float rounding of positions), while a
         * systematic error such as wrong fog changes large areas by a level or two.
         */
        val identical: Boolean
            get() = pixels > 0 && changed <= pixels * MAX_DIFFERING_FRACTION

        override fun toString() =
            "$differing of $pixels pixels differ, $changed changed at all, max channel difference $maxChannelDifference"
    }

    /** Compares two pictures of the same size pixel by pixel; differently sized pictures differ everywhere. */
    fun compare(a: NativeImage, b: NativeImage): FrameDiff {
        if (a.width != b.width || a.height != b.height) return FrameDiff(a.width * a.height, a.width * a.height, 255)
        var differing = 0
        var changed = 0
        var maxDifference = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val p = a.getPixel(x, y)
                val q = b.getPixel(x, y)
                var difference = 0
                var shift = 0
                while (shift < 24) {
                    difference = max(difference, abs(((p ushr shift) and 0xFF) - ((q ushr shift) and 0xFF)))
                    shift += 8
                }
                maxDifference = max(maxDifference, difference)
                if (difference > 0) changed++
                if (difference > CHANNEL_THRESHOLD) differing++
            }
        }
        return FrameDiff(a.width * a.height, differing, maxDifference, changed)
    }

    fun request(): Boolean {
        if (!available) return false
        requested = true
        return true
    }
}
