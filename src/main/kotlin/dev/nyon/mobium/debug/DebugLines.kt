package dev.nyon.mobium.debug

import dev.nyon.mobium.cache.ChestPrototype
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer
import net.minecraft.client.gui.components.debug.DebugScreenEntry
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import java.util.Locale

/**
 * F3 entry `mobium:timings`: the [Sections] timers averaged over the last [Sections.HISTORY] frames. Enable it in the
 * debug options screen; while it is shown, the timers run.
 */
object DebugLines : DebugScreenEntry {
    @JvmField
    val ID: Identifier = Identifier.fromNamespaceAndPath("mobium", "timings")

    override fun display(
        displayer: DebugScreenDisplayer,
        serverOrClientLevel: Level?,
        clientChunk: LevelChunk?,
        serverChunk: LevelChunk?
    ) {
        Sections.debugShownFrame = Sections.frame
        displayer.addLine(
            "Mobium extract E ${ms(Sections.EXTRACT_ENTITIES)} BE ${ms(Sections.EXTRACT_BLOCK_ENTITIES)}" +
                " | submit E ${ms(Sections.SUBMIT_ENTITIES)} BE ${ms(Sections.SUBMIT_BLOCK_ENTITIES)} ms"
        )
        displayer.addLine(
            "Mobium pose E ${ms(Sections.POSE_ENTITIES)} BE ${ms(Sections.POSE_BLOCK_ENTITIES)}" +
                " | tessellate E ${ms(Sections.TESSELLATE_ENTITIES)} BE ${ms(Sections.TESSELLATE_BLOCK_ENTITIES)} ms"
        )
        displayer.addLine(
            "Mobium prepare ${ms(Sections.PREPARE)} | draw ${ms(Sections.DRAW)} ms" +
                " | ${Sections.averageEntities()} entities, ${Sections.averageBlockEntities()} block entities"
        )
        ChestPrototype.debugLine()?.let(displayer::addLine)
    }

    private fun ms(section: Int) = String.format(Locale.ROOT, "%.2f", Sections.averageMillis(section))
}
