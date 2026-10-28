package dev.nyon.mobium.bench

import dev.nyon.mobium.mixins.OptionsAccessor
import net.minecraft.client.Options

/**
 * The game options `/mobium benchmark` measures with, so that players' results compare: render distance, FOV and
 * entity distance as in docs/benchmarks.md, and no frame cap or VSync, which would hide the difference. [apply]
 * remembers the player's values, [restore] puts them back and saves `options.txt`, in case the game saved the
 * benchmark's values in between (it does on F11). The render and entity distance are changed as if a graphics preset
 * were being applied, so the player's preset does not turn into Custom.
 */
object BenchOptions {
    const val RENDER_DISTANCE = 12
    const val FOV = 70
    const val ENTITY_DISTANCE = 1.0

    private var saved = false
    private var renderDistance = RENDER_DISTANCE
    private var entityDistance = ENTITY_DISTANCE
    private var fov = FOV
    private var framerateLimit = Options.UNLIMITED_FRAMERATE_CUTOFF
    private var vsync = false

    fun apply(options: Options) {
        if (!saved) {
            renderDistance = options.renderDistance().get()
            entityDistance = options.entityDistanceScaling().get()
            fov = options.fov().get()
            framerateLimit = options.framerateLimit().get()
            vsync = options.enableVsync().get()
            saved = true
        }
        set(options, RENDER_DISTANCE, ENTITY_DISTANCE, FOV, Options.UNLIMITED_FRAMERATE_CUTOFF, false)
    }

    /** Puts the player's values back; does nothing unless [apply] ran since the last restore. */
    fun restore(options: Options) {
        if (!saved) return
        saved = false
        set(options, renderDistance, entityDistance, fov, framerateLimit, vsync)
        options.save()
    }

    private fun set(options: Options, renderDistance: Int, entityDistance: Double, fov: Int, framerateLimit: Int, vsync: Boolean) {
        val accessor = options as OptionsAccessor
        val applying = accessor.`mobium$isApplyingGraphicsPreset`()
        accessor.`mobium$setApplyingGraphicsPreset`(true)
        try {
            options.renderDistance().set(renderDistance)
            options.entityDistanceScaling().set(entityDistance)
        } finally {
            accessor.`mobium$setApplyingGraphicsPreset`(applying)
        }
        options.fov().set(fov)
        options.framerateLimit().set(framerateLimit)
        options.enableVsync().set(vsync)
    }
}
