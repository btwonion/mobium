package dev.nyon.mobium.debug

import dev.nyon.mobium.cache.StaticCache
import dev.nyon.mobium.core.Fallback
import dev.nyon.mobium.core.ModuleStates
import dev.nyon.mobium.gpu.Gpu
import dev.nyon.mobium.parts.PartTransform
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer
import net.minecraft.client.gui.components.debug.DebugScreenEntry
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import java.util.Locale

/**
 * F3 entry `mobium:modules`: each module's state (active, or why it takes the vanilla path), the A/B key, Mobium's
 * GPU memory, what the modules hold, and the types the fallback switched off. Enable it in the debug options screen.
 */
object ModuleLines : DebugScreenEntry {
    @JvmField
    val ID: Identifier = Identifier.fromNamespaceAndPath("mobium", "modules")

    override fun display(
        displayer: DebugScreenDisplayer,
        serverOrClientLevel: Level?,
        clientChunk: LevelChunk?,
        serverChunk: LevelChunk?
    ) {
        val ab = if (AbToggle.mobiumOff) " [A/B: vanilla]" else ""
        displayer.addLine("Mobium$ab " + ModuleStates.all().joinToString(" | ") { "${it.module.key} ${it.describe()}" })
        val backend = Gpu.backend
        displayer.addLine(
            String.format(
                Locale.ROOT, "Mobium GPU memory %.1f MB in %d buffers", backend.bytesAllocated / 1048576.0,
                backend.buffersAllocated
            )
        )
        displayer.addLine(StaticCache.debugLine())
        displayer.addLine(PartTransform.debugLine())
        val disabled = Fallback.disabledTypes()
        if (disabled.isNotEmpty()) displayer.addLine("Mobium types on vanilla after failures: ${disabled.joinToString()}")
    }
}
