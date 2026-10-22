package dev.nyon.mobium.debug

import com.mojang.blaze3d.platform.NativeImage
import dev.nyon.mobium.Mobium
import dev.nyon.mobium.core.Compat
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

/**
 * Validation mode (PLAN.md §5.3, dev builds only): draws a frame both ways, Mobium's and vanilla's, and compares the
 * pictures pixel by pixel, so a difference shows up as a number instead of relying on someone looking.
 *
 * The two pictures come from consecutive frames ([FramePair]): at the start of a frame the last frame (Mobium) is
 * captured and Mobium is switched to vanilla through the A/B state, at the start of the next that vanilla frame is
 * captured and Mobium is switched back. Both frames belong to one client tick when possible, because vanilla's lightmap
 * flickers block light randomly every tick. `/mobium debug validate` does this once and reports in chat; the dev
 * self-check and its scenarios use the same pairs. Anything that moves between two frames (mobs, particles, a moving
 * camera) differs on its own, so validate a still scene.
 */
object Validation {
    /** Only development environments (dev clients, gametests) can enable it. */
    val available: Boolean
        get() = Compat.isDevelopment

    /** Client ticks so far; a pair whose frames saw different values had a tick in between. */
    var clientTicks = 0
        private set

    private var pair: FramePair? = null
    private var requested: FramePair? = null
    private var attempts = 0

    /** A pair is taken again when a client tick fell between its two frames, at most this often. */
    const val PAIR_ATTEMPTS = 5

    /** The result of the last `/mobium debug validate`. */
    var lastResult: FrameDiff? = null
        private set

    /**
     * Two pictures of consecutive frames, advanced by [onFrameStart]. With [toggle] the A/B state is flipped after
     * the first picture and back after the second.
     */
    class FramePair(val toggle: Boolean = true) {
        var stage = 0
            internal set
        var first: NativeImage? = null
            internal set
        var second: NativeImage? = null
            internal set
        var firstTick = 0
            internal set
        var secondTick = 0
            internal set
        internal var pending = 0

        val done: Boolean
            get() = stage == 2 && pending == 0

        /** True when no client tick fell between the two frames. */
        val sameTick: Boolean
            get() = firstTick == secondTick
    }

    /** Starts a pair; the caller waits for [FramePair.done] and closes both images. */
    fun capturePair(toggle: Boolean = true): FramePair = FramePair(toggle).also { pair = it }

    /** Called at the start of every frame (`Minecraft.runTick`), before the frame's client ticks. */
    @JvmStatic
    fun onFrameStart() {
        val pair = pair ?: return
        if (pair.stage >= 2) return
        val stage = pair.stage
        pair.pending++
        Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget()) { image ->
            if (stage == 0) pair.first = image else pair.second = image
            pair.pending--
        }
        if (stage == 0) pair.firstTick = clientTicks else pair.secondTick = clientTicks
        if (pair.toggle) AbToggle.toggle()
        pair.stage++
        if (pair.stage == 2) this.pair = null
    }

    /** Called once per client tick: counts ticks and reports a finished `/mobium debug validate`. */
    @JvmStatic
    fun tick() {
        clientTicks++
        val request = requested ?: return
        if (!request.done) return
        val first = request.first
        val second = request.second
        if (first == null || second == null) {
            requested = null
            return
        }
        if (!request.sameTick && ++attempts < PAIR_ATTEMPTS) {
            first.close()
            second.close()
            requested = capturePair()
            return
        }
        requested = null
        val minecraft = Minecraft.getInstance()
        val diff = compare(first, second)
        lastResult = diff
        val directory = minecraft.gameDirectory.toPath().resolve("mobium-validate")
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        try {
            Files.createDirectories(directory)
            first.writeToFile(directory.resolve("$stamp-mobium.png"))
            second.writeToFile(directory.resolve("$stamp-vanilla.png"))
        } catch (e: Exception) {
            Mobium.LOGGER.warn("Could not save the validation pictures", e)
        } finally {
            first.close()
            second.close()
        }
        val verdict = if (diff.identical) "mobium.debug.validate_identical" else "mobium.debug.validate_different"
        val ticks = if (request.sameTick) "same tick" else "a tick between the frames"
        Mobium.LOGGER.info("Mobium validation: {} ({})", diff, ticks)
        minecraft.gui.hud.chat.addClientSystemMessage(
            Component.translatable(verdict, diff.toString(), directory.resolve(stamp).toString())
        )
    }

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

    /** `/mobium debug validate`: compares the next two frames and reports in chat; false outside dev builds. */
    fun request(): Boolean {
        if (!available || requested != null) return false
        attempts = 0
        requested = capturePair()
        return true
    }
}
