package dev.nyon.mobium.bench

import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.scores.ScoreHolder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Builds a benchmark scene of the `mobium-bench` datapack through the integrated server and tells when it is ready
 * and when the game has calmed down after it. Shared by [BenchSuite] and the dev self-check.
 */
class SceneBuilder {
    /** Set on the server thread once the datapack reports the scene as built. */
    val ready = AtomicBoolean(false)

    /** Set on the server thread when the world has no `mobium-bench` datapack. */
    val missingPack = AtomicBoolean(false)

    /** Queues the scene's function for the player; false without a singleplayer world. */
    fun build(minecraft: Minecraft, scene: String): Boolean {
        val server = minecraft.singleplayerServer ?: return false
        val player = minecraft.player ?: return false
        ready.set(false)
        missingPack.set(false)
        val uuid = player.uuid
        server.execute {
            if (server.functions.get(Identifier.fromNamespaceAndPath(NAMESPACE, scene)).isEmpty) {
                missingPack.set(true)
                return@execute
            }
            server.commands.performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(),
                "execute as $uuid at @s run function $NAMESPACE:$scene"
            )
        }
        return true
    }

    /** Asks the server whether the datapack set `#ready` to 1; runs after the build command in the server's queue. */
    fun poll(minecraft: Minecraft) {
        val server = minecraft.singleplayerServer ?: return
        server.execute { ready.set(isReady(server)) }
    }

    private fun isReady(server: MinecraftServer): Boolean {
        val objective = server.scoreboard.getObjective(OBJECTIVE) ?: return false
        return server.scoreboard.getPlayerScoreInfo(ScoreHolder.forNameOnly("#ready"), objective)?.value() == 1
    }

    companion object {
        const val NAMESPACE = "mobium-bench"
        private const val OBJECTIVE = "mobium_bench"
        private const val QUIET_TICK_NANOS = 25_000_000L

        /**
         * Whether the world has the datapack's function for [scene]. Read on the render thread before a plan starts,
         * so that it can refuse at once; the server only replaces the function map on `/reload`.
         */
        fun hasScene(server: MinecraftServer, scene: String): Boolean =
            server.functions.get(Identifier.fromNamespaceAndPath(NAMESPACE, scene)).isPresent

        /**
         * A freshly built scene keeps the game busy for a while (section rebuilds, client light updates, server
         * lighting); measuring before that ends would penalise whichever run comes first.
         */
        fun isQuiet(minecraft: Minecraft): Boolean {
            val level = minecraft.level ?: return false
            if (!minecraft.levelRenderer.hasRenderedAllSections() || level.lightEngine.hasLightWork()) return false
            val server = minecraft.singleplayerServer ?: return true
            return server.averageTickTimeNanos < QUIET_TICK_NANOS
        }
    }
}
