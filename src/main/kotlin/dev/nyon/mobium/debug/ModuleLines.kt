package dev.nyon.mobium.debug

import dev.nyon.mobium.cache.StaticCache
import dev.nyon.mobium.core.Fallback
import dev.nyon.mobium.core.ModuleStates
import dev.nyon.mobium.gpu.Gpu
import dev.nyon.mobium.offload.OffloadCheck
import dev.nyon.mobium.offload.PartTransform
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer
import net.minecraft.client.gui.components.debug.DebugScreenEntry
//? if >=26.4 {
/*import net.minecraft.client.core.registries.ClientRegistries
import net.minecraft.client.gui.components.debug.DebugGroup
import net.minecraft.resources.ResourceKey
*///?}
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import java.util.Locale

/**
 * F3 entry `mobium:modules`: each module's state (active, or why it takes the vanilla path), the A/B key, Mobium's
 * GPU memory, what the modules hold, vertex offload's GPU check ([OffloadCheck]), and the types the fallback switched
 * off. Enable it in the debug options screen.
 */
object ModuleLines : DebugScreenEntry {
    @JvmField
    val ID: Identifier = Identifier.fromNamespaceAndPath("mobium", "modules")

    // What the entry is registered and switched on with: the id in 26.3, a key of the client registry in 26.4; and the
    // group that keeps its lines together.
    //? if >=26.4 {
    /*@JvmField
    val KEY: ResourceKey<DebugScreenEntry> = ResourceKey.create(ClientRegistries.DEBUG_SCREEN_ENTRY, ID)
    private val GROUP: DebugGroup = DebugGroup.Builder.titleless().build()
    *///?} else {
    @JvmField
    val KEY: Identifier = ID
    private val GROUP: Identifier = ID
    //?}

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
        lines += OffloadCheck.debugLine()
        val disabled = Fallback.disabledTypes()
        if (disabled.isNotEmpty()) lines += "Mobium types on vanilla after failures: ${disabled.joinToString()}"
        // A group keeps the lines together in one column; vanilla splits plain lines between both columns, which put
        // the long ones on the right, over the left column's.
        displayer.addToGroup(GROUP, lines)
    }
}
