package dev.nyon.mobium.bench

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Client commands `/mobium bench <scene> <seconds> [setup]` and `/mobium stop`. The tree is built for any command
 * source type, so the same code registers with Fabric's client command API and NeoForge's
 * `RegisterClientCommandsEvent`. The scene is only a label; build it first with the benchmark datapack.
 */
object BenchCommand {
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 600

    fun <S> create(): LiteralArgumentBuilder<S> = LiteralArgumentBuilder.literal<S>("mobium")
        .then(
            LiteralArgumentBuilder.literal<S>("bench").then(
                RequiredArgumentBuilder.argument<S, String>("scene", StringArgumentType.word()).then(
                    RequiredArgumentBuilder.argument<S, Int>(
                        "seconds", IntegerArgumentType.integer(MIN_SECONDS, MAX_SECONDS)
                    )
                        .executes { start(it, null) }
                        .then(
                            RequiredArgumentBuilder.argument<S, String>("setup", StringArgumentType.word())
                                .executes { start(it, StringArgumentType.getString(it, "setup")) }
                        )
                )
            )
        )
        .then(LiteralArgumentBuilder.literal<S>("stop").executes {
            if (!FrameRecorder.stop()) chat(Component.translatable("mobium.bench.idle"))
            1
        })

    private fun <S> start(context: CommandContext<S>, setup: String?): Int {
        val scene = StringArgumentType.getString(context, "scene")
        val seconds = IntegerArgumentType.getInteger(context, "seconds")
        if (!FrameRecorder.start(scene, seconds, setup)) {
            chat(Component.translatable("mobium.bench.busy"))
            return 0
        }
        return 1
    }

    private fun chat(message: Component) = Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(message)
}
