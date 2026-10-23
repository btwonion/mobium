package dev.nyon.mobium.cache

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap

/**
 * When each chunk section last had a client light update, as a light epoch: a counter bumped by every update. A cached
 * object remembers the epoch it last read its light at; its light can only have changed if one of its sections was
 * updated after that, so a light update elsewhere costs it one lookup per section, not a re-read. Without Minecraft
 * classes (sections are `SectionPos` keys), unit-tested. Render thread only.
 */
class LightStamps {
    /** The epoch of each section's last light update; sections without one are absent and read as 0. */
    private val stamps = Long2LongOpenHashMap()

    /** Bumped by every light update; an object that read its light at this epoch has seen every update so far. */
    var epoch = 0L
        private set

    val size: Int
        get() = stamps.size

    fun update(sectionKey: Long) {
        epoch++
        stamps.put(sectionKey, epoch)
    }

    /** True if the section had a light update after [checkedAt]. */
    fun changedSince(sectionKey: Long, checkedAt: Long): Boolean = stamps.get(sectionKey) > checkedAt

    /**
     * Forgets the sections whose last update is at or before [oldestCheck], the lowest epoch any object read its light
     * at: every object has seen those updates, and a forgotten section reads as never updated.
     */
    fun prune(oldestCheck: Long) {
        val iterator = stamps.long2LongEntrySet().fastIterator()
        while (iterator.hasNext()) {
            if (iterator.next().longValue <= oldestCheck) iterator.remove()
        }
    }

    /** Forgets every section; the epoch keeps counting, so no object can mistake a later update for an old one. */
    fun clear() {
        stamps.clear()
    }
}
