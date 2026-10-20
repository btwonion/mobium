package dev.nyon.mobium.test

import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

/**
 * Pixel comparison of two gametest screenshots of the same view. A pixel differs when any colour channel differs by
 * more than [THRESHOLD]; differing pixels are painted red and pixels that changed by less yellow into a diff image next
 * to the second screenshot. [changedPixels] counts every pixel that is not exactly equal.
 */
data class ScreenshotDiff(
    val width: Int,
    val height: Int,
    val differingPixels: Int,
    val maxChannelDifference: Int,
    val diffImage: Path,
    val changedPixels: Int = differingPixels
) {
    val differingFraction: Double
        get() = differingPixels.toDouble() / (width * height)

    val changedFraction: Double
        get() = changedPixels.toDouble() / (width * height)

    override fun toString() =
        "$differingPixels of ${width * height} pixels differ (${"%.4f".format(differingFraction * 100)} %), " +
            "$changedPixels changed at all, max channel difference $maxChannelDifference"

    companion object {
        const val THRESHOLD = 16

        fun compare(expected: Path, actual: Path): ScreenshotDiff {
            val a = ImageIO.read(expected.toFile())
            val b = ImageIO.read(actual.toFile())
            check(a.width == b.width && a.height == b.height) { "Screenshot sizes differ: $expected vs $actual" }

            val diff = BufferedImage(a.width, a.height, BufferedImage.TYPE_INT_RGB)
            var differing = 0
            var changed = 0
            var maxDifference = 0
            for (y in 0 until a.height) for (x in 0 until a.width) {
                val p = a.getRGB(x, y)
                val q = b.getRGB(x, y)
                val d = max(channel(p, q, 16), max(channel(p, q, 8), channel(p, q, 0)))
                maxDifference = max(maxDifference, d)
                if (d > 0) changed++
                if (d > THRESHOLD) {
                    differing++
                    diff.setRGB(x, y, 0xFF0000)
                } else if (d > 0) {
                    diff.setRGB(x, y, 0xFFFF00)
                } else {
                    diff.setRGB(x, y, (p shr 2) and 0x3F3F3F)
                }
            }
            val file = actual.resolveSibling(actual.fileName.toString().removeSuffix(".png") + "_diff.png")
            ImageIO.write(diff, "png", file.toFile())
            return ScreenshotDiff(a.width, a.height, differing, maxDifference, file, changed)
        }

        private fun channel(p: Int, q: Int, shift: Int) = abs(((p shr shift) and 0xFF) - ((q shr shift) and 0xFF))
    }
}
