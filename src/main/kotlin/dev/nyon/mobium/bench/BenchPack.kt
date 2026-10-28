package dev.nyon.mobium.bench

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.repository.PackSource
import java.util.function.UnaryOperator

/**
 * The benchmark datapack as a built-in data pack of the jar, so players can run `/mobium benchmark` with the release:
 * the build copies `benchmark/mobium-bench` to [PATH] (build.gradle.kts, `benchPack`), and the loader entrypoints
 * register it. It is listed under Create New World → Data Packs and never switched on by itself, neither in new worlds
 * nor in existing ones, so its scenes are only ever built in a world made for them (docs/vanilla-notes.md §17).
 */
object BenchPack {
    /** Where the pack lies inside the jar; Fabric's built-in packs must be under `resourcepacks/`. */
    const val PATH = "resourcepacks/mobium-bench"

    /** The pack's id in the pack repository: Fabric uses the registered identifier, NeoForge adds `mod/`. */
    /*? if fabric {*/
    const val ID = "mobium:mobium-bench"
    /*?} else {*/
    /*const val ID = "mod/mobium:$PATH"
    *//*?}*/

    val NAME: Component
        get() = Component.translatable("pack.mobium.benchmark")

    /**
     * NeoForge only: shown as "(built-in)" like vanilla's own packs, but not added automatically, which
     * [PackSource.BUILT_IN] would be in every new and existing world (`MinecraftServer.configurePackRepository`).
     */
    val SOURCE: PackSource = PackSource.create(
        UnaryOperator { name ->
            Component.translatable("pack.nameAndSource", name, Component.translatable("pack.source.builtin"))
                .withStyle(ChatFormatting.GRAY)
        },
        false
    )
}
