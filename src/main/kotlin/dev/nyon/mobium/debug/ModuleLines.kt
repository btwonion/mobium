package dev.nyon.mobium.debug

import dev.nyon.mobium.cache.StaticCache
import dev.nyon.mobium.core.Fallback
import dev.nyon.mobium.core.ModuleStates
import dev.nyon.mobium.gpu.Gpu
import dev.nyon.mobium.parts.PartCheck
import dev.nyon.mobium.parts.PartTransform
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer
import net.minecraft.client.gui.components.debug.DebugScreenEntry
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import java.util.Locale

/**
 * F3 entry `mobium:modules`: each module's state (active, or why it takes the vanilla path), the A/B key, Mobium's
 * GPU memory, what the modules hold, Module 2's GPU check ([PartCheck]), and the types the fallback switched off.
 * Enable it in the debug options screen.
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
        val lines = ArrayList<String>()
        lines += "Mobium$ab " + ModuleStates.all().joinToString(" | ") { "${it.module.key} ${it.describe()}" }
        val backend = Gpu.backend
        lines += String.format(
            Locale.ROOT, "Mobium GPU memory %.1f MB in %d buffers", backend.bytesAllocated / 1048576.0,
            backend.buffersAllocated
        )
        lines += StaticCache.debugLines()
        lines += PartTransform.debugLine()
        lines += PartCheck.debugLine()
        val disabled = Fallback.disabledTypes()
        if (disabled.isNotEmpty()) lines += "Mobium types on vanilla after failures: ${disabled.joinToString()}"
        // A group keeps the lines together in one column; vanilla splits plain lines between both columns, which put
        // the long ones on the right, over the left column's.
        displayer.addToGroup(ID, lines)
    }
}
