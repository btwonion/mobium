package dev.nyon.mobium.debug

import net.minecraft.util.profiling.Profiler

/**
 * Per-frame CPU timers around the stages of entity and block entity rendering (docs/vanilla-notes.md §0), split by
 * entities and block entities where vanilla allows it. Mixins call [begin]/[end] around a stage; [endFrame] closes the
 * frame. The coarse stages are also pushed onto the vanilla profiler so they show up in the F3 pie chart and in
 * Tracy, and spark sees the `mobium$` handlers in its call tree.
 *
 * Timing only runs while [enabled] (a benchmark is recording or the F3 entry is visible); otherwise [begin] returns 0
 * and [end] does nothing. Render thread only.
 */
object Sections {
    const val EXTRACT_ENTITIES = 0
    const val EXTRACT_BLOCK_ENTITIES = 1
    const val SUBMIT_ENTITIES = 2
    const val SUBMIT_BLOCK_ENTITIES = 3
    const val POSE_ENTITIES = 4
    const val POSE_BLOCK_ENTITIES = 5
    const val TESSELLATE_ENTITIES = 6
    const val TESSELLATE_BLOCK_ENTITIES = 7

    /** The level's whole prepare stage: every feature renderer's vertex writing plus the upload. */
    const val PREPARE = 8

    /** CPU time spent issuing the level's feature draws (all phases, including OIT stages; not Iris's shadow pass). */
    const val DRAW = 9

    /**
     * Iris's shadow pass as a whole (`ShadowRenderer.renderShadows`: shadow terrain, entities, block entities and their
     * draws), which runs inside the level render but outside the stages above; 0 without a shader pack. The next
     * sections split its entity and block entity work the same way as the level's.
     */
    const val SHADOW = 10
    const val SHADOW_EXTRACT = 11
    const val SHADOW_SUBMIT = 12
    const val SHADOW_POSE = 13
    const val SHADOW_TESSELLATE_ENTITIES = 14
    const val SHADOW_TESSELLATE_BLOCK_ENTITIES = 15
    const val SHADOW_PREPARE = 16
    const val SHADOW_DRAW = 17
    const val COUNT = 18

    /** Column and profiler names, indexed by section. */
    @JvmField
    val NAMES = arrayOf(
        "extract_entities",
        "extract_block_entities",
        "submit_entities",
        "submit_block_entities",
        "pose_entities",
        "pose_block_entities",
        "tessellate_entities",
        "tessellate_block_entities",
        "prepare",
        "draw",
        "shadow",
        "shadow_extract",
        "shadow_submit",
        "shadow_pose",
        "shadow_tessellate_entities",
        "shadow_tessellate_block_entities",
        "shadow_prepare",
        "shadow_draw"
    )

    private val PROFILER_NAMES = Array(COUNT) { "mobium:${NAMES[it]}" }

    const val HISTORY = 64

    @JvmField
    var enabled = false

    /** Keeps the timers running without a benchmark or the F3 entry (the dev self-check reads them). */
    @JvmField
    var forceTimers = false

    /** True while vanilla's level submit loop runs over block entities; read by model submits to tag their origin. */
    @JvmField
    var submittingBlockEntities = false

    /** True while the level's own prepare stage runs (not the hand, GUI or Iris's shadow pass). */
    @JvmField
    var inLevelPrepare = false

    /** True while Iris's shadow pass runs; its feature draws count as [SHADOW_DRAW], not [DRAW]. */
    @JvmField
    var inShadowPass = false

    /** True while the shadow pass's prepare stage runs; its models count as [SHADOW_POSE] and shadow tessellation. */
    @JvmField
    var inShadowPrepare = false

    /** Start times of the sections timed by separate begin and end injections ([open], [close]). */
    private val openStarts = LongArray(COUNT)

    @JvmField
    val current = LongArray(COUNT)

    @JvmField
    val lastFrame = LongArray(COUNT)

    @JvmField
    var entities = 0

    @JvmField
    var blockEntities = 0

    @JvmField
    var lastEntities = 0

    @JvmField
    var lastBlockEntities = 0

    @JvmField
    var frame = 0L

    @JvmField
    var debugShownFrame = Long.MIN_VALUE

    private val history = LongArray(HISTORY * COUNT)
    private val entityHistory = IntArray(HISTORY)
    private val blockEntityHistory = IntArray(HISTORY)
    private var historySize = 0

    @JvmStatic
    fun begin(): Long = if (enabled) System.nanoTime() else 0L

    @JvmStatic
    fun end(section: Int, start: Long) {
        if (start != 0L) current[section] += System.nanoTime() - start
    }

    @JvmStatic
    fun beginProfiled(section: Int): Long {
        Profiler.get().push(PROFILER_NAMES[section])
        return begin()
    }

    @JvmStatic
    fun endProfiled(section: Int, start: Long) {
        end(section, start)
        Profiler.get().pop()
    }

    /** Starts [section] for a stage whose start and end are separate injections (Iris's shadow pass). */
    @JvmStatic
    fun open(section: Int) {
        openStarts[section] = begin()
    }

    @JvmStatic
    fun close(section: Int) {
        end(section, openStarts[section])
        openStarts[section] = 0L
    }

    @JvmStatic
    fun beginShadowPass() {
        inShadowPass = true
        open(SHADOW)
    }

    @JvmStatic
    fun endShadowPass() {
        close(SHADOW)
        inShadowPass = false
    }

    /** True while a prepare stage whose models are timed runs: the level's own or the shadow pass's. */
    @JvmStatic
    fun inTimedPrepare(): Boolean = inLevelPrepare || inShadowPrepare

    /** The pose section for a model submitted by a block entity or an entity, in the prepare stage running now. */
    @JvmStatic
    fun poseSection(blockEntity: Boolean): Int = when {
        inShadowPrepare -> SHADOW_POSE
        blockEntity -> POSE_BLOCK_ENTITIES
        else -> POSE_ENTITIES
    }

    /** The tessellation section for a model submitted by a block entity or an entity, in the prepare stage running now. */
    @JvmStatic
    fun tessellateSection(blockEntity: Boolean): Int = when {
        inShadowPrepare -> if (blockEntity) SHADOW_TESSELLATE_BLOCK_ENTITIES else SHADOW_TESSELLATE_ENTITIES
        blockEntity -> TESSELLATE_BLOCK_ENTITIES
        else -> TESSELLATE_ENTITIES
    }

    /** Closes the current frame: publishes its totals to [lastFrame] and the F3 history, then resets. */
    @JvmStatic
    fun endFrame(recording: Boolean) {
        val slot = (frame % HISTORY).toInt()
        for (i in 0 until COUNT) {
            lastFrame[i] = current[i]
            history[slot * COUNT + i] = current[i]
            current[i] = 0L
        }
        lastEntities = entities
        lastBlockEntities = blockEntities
        entityHistory[slot] = entities
        blockEntityHistory[slot] = blockEntities
        entities = 0
        blockEntities = 0
        if (historySize < HISTORY) historySize++
        // A shadow pass that ended with an exception left its flags set; they never outlast the frame.
        inShadowPass = false
        inShadowPrepare = false
        openStarts.fill(0L)
        frame++
        enabled = recording || forceTimers || frame - debugShownFrame <= 2
    }

    /** Average of [section] over the recorded history, in milliseconds. */
    fun averageMillis(section: Int): Double {
        if (historySize == 0) return 0.0
        var sum = 0L
        for (i in 0 until historySize) sum += history[i * COUNT + section]
        return sum.toDouble() / historySize / 1_000_000.0
    }

    fun averageEntities(): Int = averageOf(entityHistory)

    fun averageBlockEntities(): Int = averageOf(blockEntityHistory)

    private fun averageOf(values: IntArray): Int {
        if (historySize == 0) return 0
        var sum = 0L
        for (i in 0 until historySize) sum += values[i]
        return (sum / historySize).toInt()
    }
}
