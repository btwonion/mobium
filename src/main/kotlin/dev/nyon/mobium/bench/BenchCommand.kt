package dev.nyon.mobium.bench

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.Fallback
import dev.nyon.mobium.core.Module
import dev.nyon.mobium.debug.Validation
import dev.nyon.mobium.offload.OffloadCheck
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Client commands `/mobium bench <scene> <seconds> [setup]`, `/mobium suite [seconds] [runs] [scenes…]`,
 * `/mobium compare <module> [seconds] [runs] [scene]`, `/mobium cachecost [seconds] [runs]`,
 * `/mobium stages [seconds] [runs] [scene]`, `/mobium benchmark` (see [BenchSuite]) and `/mobium stop`. The tree is
 * built for any command source type, so the same code registers with Fabric's client command API and NeoForge's
 * `RegisterClientCommandsEvent`. The scene is only a label; build it first with the benchmark datapack.
 */
object BenchCommand {
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 600
    const val MAX_RUNS = 20
    private const val STAGES_SCENE = "mob_pen_live"

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
            LiteralArgumentBuilder.literal<S>("compare")
                .then(compareModule(Module.STATIC_CACHE))
                .then(compareModule(Module.VERTEX_OFFLOAD))
        )
        .then(
            LiteralArgumentBuilder.literal<S>("cachecost")
                .executes { cacheCost(BenchSuite.DEFAULT_SECONDS, BenchSuite.DEFAULT_RUNS) }
                .then(
                    RequiredArgumentBuilder.argument<S, Int>("seconds", IntegerArgumentType.integer(MIN_SECONDS, MAX_SECONDS))
                        .executes { cacheCost(seconds(it), BenchSuite.DEFAULT_RUNS) }
                        .then(
                            RequiredArgumentBuilder.argument<S, Int>("runs", IntegerArgumentType.integer(1, MAX_RUNS))
                                .executes { cacheCost(seconds(it), runs(it)) }
                        )
                )
        )
        .then(
            LiteralArgumentBuilder.literal<S>("stages")
                .executes { stages(BenchSuite.DEFAULT_SECONDS, BenchSuite.DEFAULT_RUNS, STAGES_SCENE) }
                .then(
                    RequiredArgumentBuilder.argument<S, Int>("seconds", IntegerArgumentType.integer(MIN_SECONDS, MAX_SECONDS))
                        .executes { stages(seconds(it), BenchSuite.DEFAULT_RUNS, STAGES_SCENE) }
                        .then(
                            RequiredArgumentBuilder.argument<S, Int>("runs", IntegerArgumentType.integer(1, MAX_RUNS))
                                .executes { stages(seconds(it), runs(it), STAGES_SCENE) }
                                .then(sceneArgument { stages(seconds(it), runs(it), scene(it)) })
                        )
                )
        )
        .then(
            LiteralArgumentBuilder.literal<S>("benchmark")
                .executes { benchmark(BenchSuite.BENCHMARK_SECONDS) }
                // Dev environments only: shorter runs for the self-check.
                .also {
                    if (Compat.isDevelopment) {
                        it.then(
                            RequiredArgumentBuilder.argument<S, Int>("seconds", IntegerArgumentType.integer(MIN_SECONDS, MAX_SECONDS))
                                .executes { context -> benchmark(seconds(context)) }
                        )
                    }
                }
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
     * the module off with one toast (docs/architecture.md, *Core*), `/mobium debug validate` requests a validation
     * frame, and `/mobium debug gpucheck` makes vertex offload's GPU check find a difference, as on a driver that
     * rounds otherwise.
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
        .then(LiteralArgumentBuilder.literal<S>("gpucheck").executes {
            OffloadCheck.forceDifference()
            chat(Component.translatable("mobium.debug.gpucheck"))
            1
        })

    /** `/mobium compare <module> [seconds] [runs] [scene]`, by default in the module's own scene. */
    private fun <S> compareModule(module: Module): LiteralArgumentBuilder<S> {
        val scene = BenchSuite.compareScene(module)
        return LiteralArgumentBuilder.literal<S>(module.key)
            .executes { compare(module, BenchSuite.DEFAULT_SECONDS, BenchSuite.DEFAULT_RUNS, scene) }
            .then(
                RequiredArgumentBuilder.argument<S, Int>("seconds", IntegerArgumentType.integer(MIN_SECONDS, MAX_SECONDS))
                    .executes { compare(module, seconds(it), BenchSuite.DEFAULT_RUNS, scene) }
                    .then(
                        RequiredArgumentBuilder.argument<S, Int>("runs", IntegerArgumentType.integer(1, MAX_RUNS))
                            .executes { compare(module, seconds(it), runs(it), scene) }
                            .then(sceneArgument { compare(module, seconds(it), runs(it), scene(it)) })
                    )
            )
    }

    /** A `scene` argument with the benchmark scenes as suggestions; the plan checks it against [BenchSuite.SCENES]. */
    private fun <S> sceneArgument(command: Command<S>): RequiredArgumentBuilder<S, String> =
        RequiredArgumentBuilder.argument<S, String>("scene", StringArgumentType.word())
            .suggests { _, builder ->
                BenchSuite.SCENES.forEach(builder::suggest)
                builder.buildFuture()
            }
            .executes(command)

    private fun <S> seconds(context: CommandContext<S>) = IntegerArgumentType.getInteger(context, "seconds")

    private fun <S> runs(context: CommandContext<S>) = IntegerArgumentType.getInteger(context, "runs")

    private fun <S> scene(context: CommandContext<S>) = StringArgumentType.getString(context, "scene")

    private fun suite(seconds: Int, runs: Int, scenes: List<String>): Int {
        BenchSuite.startSuite(seconds, runs, scenes)?.let { chat(it); return 0 }
        return 1
    }

    private fun compare(module: Module, seconds: Int, runs: Int, scene: String): Int {
        BenchSuite.startCompare(module, seconds, runs, scene)?.let { chat(it); return 0 }
        return 1
    }

    private fun cacheCost(seconds: Int, runs: Int): Int {
        BenchSuite.startCacheCost(seconds, runs)?.let { chat(it); return 0 }
        return 1
    }

    private fun stages(seconds: Int, runs: Int, scene: String): Int {
        BenchSuite.startStages(seconds, runs, scene)?.let { chat(it); return 0 }
        return 1
    }

    private fun benchmark(seconds: Int): Int {
        BenchSuite.startBenchmark(seconds)?.let { chat(it); return 0 }
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
