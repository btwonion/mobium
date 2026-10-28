package dev.nyon.mobium.parts

/**
 * Which parts [PartCheck] compares with the CPU: per path (a small integer below [paths]) the first [perPath] parts,
 * at most [perFrame] of them per frame, so that a path's samples come from several frames (other poses) and a frame
 * full of one model costs at most [perFrame] comparisons per path. Without Minecraft classes, unit-tested. Render
 * thread only; [take] runs per part and allocates nothing.
 */
class PathSampler(private val paths: Int, private val perPath: Int, private val perFrame: Int) {
    private val taken = IntArray(paths)
    private val takenThisFrame = IntArray(paths)

    /** The paths with samples this frame (the first [touchedCount] entries), to reset at [endFrame]. */
    private val touched = IntArray(paths)
    private var touchedCount = 0

    /** Parts taken since the start or the last [reset]. */
    var total = 0
        private set

    /** Paths that have had at least one part taken. */
    var pathsSeen = 0
        private set

    /** Whether to compare the next part of [path]; counts it if so. */
    fun take(path: Int): Boolean {
        if (path < 0 || path >= paths) return false
        if (taken[path] >= perPath || takenThisFrame[path] >= perFrame) return false
        if (taken[path] == 0) pathsSeen++
        if (takenThisFrame[path] == 0) touched[touchedCount++] = path
        taken[path]++
        takenThisFrame[path]++
        total++
        return true
    }

    fun endFrame() {
        for (i in 0 until touchedCount) takenThisFrame[touched[i]] = 0
        touchedCount = 0
    }

    /** Every path starts over. */
    fun reset() {
        taken.fill(0)
        takenThisFrame.fill(0)
        touchedCount = 0
        total = 0
        pathsSeen = 0
    }
}
