package dev.nyon.mobium.bench

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.Fallback
import dev.nyon.mobium.core.Module
import dev.nyon.mobium.debug.Validation
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Client commands `/mobium bench <scene> <seconds> [setup]`, `/mobium suite [seconds] [runs] [scenes…]`,
 * `/mobium gatea [seconds] [runs]`, `/mobium gateb [seconds] [runs]`, `/mobium gatec [seconds] [runs]` (see
 * [BenchSuite]) and `/mobium stop`. The tree is built for any command
 * source type, so the same code registers with Fabric's client command API and NeoForge's
 * `RegisterClientCommandsEvent`. The scene is only a label; build it first with the benchmark datapack.
 */
object BenchCommand {
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 600
    const val MAX_RUNS = 20

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
        .then(
            LiteralArgumentBuilder.literal<S>("suite")
                .executes { suite(BenchSuite.DEFAULT_SECONDS, BenchSuite.DEFAULT_RUNS, BenchSuite.SCENES) }
                .then(
                    RequiredArgumentBuilder.argument<S, Int>("seconds", IntegerArgumentType.integer(MIN_SECONDS, MAX_SECONDS))
                        .executes { suite(seconds(it), BenchSuite.DEFAULT_RUNS, BenchSuite.SCENES) }
                        .then(
                            RequiredArgumentBuilder.argument<S, Int>("runs", IntegerArgumentType.integer(1, MAX_RUNS))
                                .executes { suite(seconds(it), runs(it), BenchSuite.SCENES) }
                                .then(
                                    RequiredArgumentBuilder.argument<S, String>("scenes", StringArgumentType.greedyString())
                                        .suggests { _, builder ->
                                            BenchSuite.SCENES.forEach(builder::suggest)
                                            builder.buildFuture()
                                        }
                                        .executes {
                                            val scenes = StringArgumentType.getString(it, "scenes").split(' ').filter(String::isNotBlank)
                                            suite(seconds(it), runs(it), scenes)
                                        }
                                )
                        )
                )
        )
        .then(
            LiteralArgumentBuilder.literal<S>("gatea")
                .executes { gateA(BenchSuite.DEFAULT_SECONDS, BenchSuite.DEFAULT_RUNS) }
                .then(
                    RequiredArgumentBuilder.argument<S, Int>("seconds", IntegerArgumentType.integer(MIN_SECONDS, MAX_SECONDS))
                        .executes { gateA(seconds(it), BenchSuite.DEFAULT_RUNS) }
                        .then(
                            RequiredArgumentBuilder.argument<S, Int>("runs", IntegerArgumentType.integer(1, MAX_RUNS))
                                .executes { gateA(seconds(it), runs(it)) }
                        )
                )
        )
        .then(
            LiteralArgumentBuilder.literal<S>("gateb")
                .executes { gateB(BenchSuite.DEFAULT_SECONDS, BenchSuite.DEFAULT_RUNS) }
                .then(
                    RequiredArgumentBuilder.argument<S, Int>("seconds", IntegerArgumentType.integer(MIN_SECONDS, MAX_SECONDS))
                        .executes { gateB(seconds(it), BenchSuite.DEFAULT_RUNS) }
                        .then(
                            RequiredArgumentBuilder.argument<S, Int>("runs", IntegerArgumentType.integer(1, MAX_RUNS))
                                .executes { gateB(seconds(it), runs(it)) }
                        )
                )
        )
        .then(
            LiteralArgumentBuilder.literal<S>("gatec")
                .executes { gateC(BenchSuite.DEFAULT_SECONDS, BenchSuite.DEFAULT_RUNS) }
                .then(
                    RequiredArgumentBuilder.argument<S, Int>("seconds", IntegerArgumentType.integer(MIN_SECONDS, MAX_SECONDS))
                        .executes { gateC(seconds(it), BenchSuite.DEFAULT_RUNS) }
                        .then(
                            RequiredArgumentBuilder.argument<S, Int>("runs", IntegerArgumentType.integer(1, MAX_RUNS))
                                .executes { gateC(seconds(it), runs(it)) }
                        )
                )
        )
        .then(LiteralArgumentBuilder.literal<S>("stop").executes {
            val suite = BenchSuite.stop()
            val run = FrameRecorder.stop()
            if (!suite && !run) chat(Component.translatable("mobium.bench.idle"))
            1
        })
        .also { if (Compat.isDevelopment) it.then(debug()) }

    /**
     * Dev environments only: `/mobium debug fail <module>` throws inside the module's fallback path, which must switch
     * the module off with one toast (PLAN.md M4), and `/mobium debug validate` requests a validation frame.
     */
    private fun <S> debug(): LiteralArgumentBuilder<S> = LiteralArgumentBuilder.literal<S>("debug")
        .then(
            LiteralArgumentBuilder.literal<S>("fail").then(
                RequiredArgumentBuilder.argument<S, String>("module", StringArgumentType.word())
                    .suggests { _, builder ->
                        Module.entries.forEach { builder.suggest(it.key) }
                        builder.buildFuture()
                    }
                    .executes {
                        val module = Module.byKey(StringArgumentType.getString(it, "module"))
                        if (module == null) {
                            chat(Component.translatable("mobium.debug.unknown_module"))
                            return@executes 0
                        }
                        Fallback.moduleFailed(module, IllegalStateException("Forced by /mobium debug fail"))
                        chat(Component.translatable("mobium.debug.failed", Component.translatable(module.translationKey)))
                        1
                    }
            )
        )
        .then(LiteralArgumentBuilder.literal<S>("validate").executes {
            chat(Component.translatable(if (Validation.request()) "mobium.debug.validate" else "mobium.debug.unavailable"))
            1
        })

    private fun <S> seconds(context: CommandContext<S>) = IntegerArgumentType.getInteger(context, "seconds")

    private fun <S> runs(context: CommandContext<S>) = IntegerArgumentType.getInteger(context, "runs")

    private fun suite(seconds: Int, runs: Int, scenes: List<String>): Int {
        BenchSuite.startSuite(seconds, runs, scenes)?.let { chat(it); return 0 }
        return 1
    }

    private fun gateA(seconds: Int, runs: Int): Int {
        BenchSuite.startGateA(seconds, runs)?.let { chat(it); return 0 }
        return 1
    }

    private fun gateB(seconds: Int, runs: Int): Int {
        BenchSuite.startGateB(seconds, runs)?.let { chat(it); return 0 }
        return 1
    }

    private fun gateC(seconds: Int, runs: Int): Int {
        BenchSuite.startGateC(seconds, runs)?.let { chat(it); return 0 }
        return 1
    }

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
