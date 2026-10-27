package dev.nyon.mobium.bench

import dev.nyon.mobium.Mobium
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ClientLevel
import org.lwjgl.sdl.SDLError
import org.lwjgl.sdl.SDLStdinc
import org.lwjgl.sdl.SDLVideo
import org.lwjgl.sdl.SDL_DisplayMode
import org.lwjgl.system.MemoryStack
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * An unattended benchmark round (`benchmark/bench_round.py`): when `<game dir>/mobium-bench/autorun` exists at client
 * start, Mobium reads and deletes it, opens its world (unless the launcher's quick play already did), puts the
 * fullscreen window on the monitor whose name contains `display`, runs each `command` line in turn as a client command
 * (`mobium suite`, `mobium gatec`, …), waiting for each plan to finish, then writes `autorun-done` next to it and quits
 * the game. File format, one `key=value` per line: `world=`, `display=` (optional), any number of `command=`. Nothing
 * happens without the file. Ticked once per client tick on the render thread.
 */
object BenchAutorun {
    private const val REQUEST = "autorun"
    private const val DONE = "autorun-done"
    private const val TICKS_PER_SECOND = 20

    /** How long the title screen may stay before the autorun opens the world itself (the launcher's quick play may). */
    private const val OPEN_WORLD_TICKS = 15 * TICKS_PER_SECOND
    private const val SETTLE_TICKS = 10 * TICKS_PER_SECOND
    private const val DISPLAY_TICKS = 5 * TICKS_PER_SECOND

    /** How long a command may take to start a plan before it counts as refused (its reason is in chat and the log). */
    private const val START_TICKS = 3 * TICKS_PER_SECOND
    private const val WORLD_TIMEOUT_TICKS = 300 * TICKS_PER_SECOND

    private enum class State { IDLE, WORLD, SETTLE, DISPLAY, START, RUNNING, QUIT }

    private var state = State.IDLE
    private var ticks = 0
    private lateinit var directory: Path
    private var world = ""
    private var display = ""
    private val commands = ArrayList<String>()
    private var next = 0
    private val log = ArrayList<String>()

    /** Reads the request file, if there is one; called once when the client has started. */
    fun startIfRequested(minecraft: Minecraft) {
        directory = minecraft.gameDirectory.toPath().resolve("mobium-bench")
        val request = directory.resolve(REQUEST)
        if (!Files.exists(request)) return
        val lines = Files.readAllLines(request)
        Files.delete(request)
        Files.deleteIfExists(directory.resolve(DONE))
        for (line in lines) {
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=', "").trim()
            when (key) {
                "world" -> world = value
                "display" -> display = value
                "command" -> if (value.isNotEmpty()) commands += value.removePrefix("/")
            }
        }
        note("started (build ${Mobium.BUILD}): world \"$world\", display \"$display\", ${commands.size} commands")
        state = State.WORLD
        ticks = 0
    }

    @JvmStatic
    fun tick(minecraft: Minecraft) {
        if (state == State.IDLE) return
        ticks++
        when (state) {
            State.WORLD -> when {
                minecraft.level != null && minecraft.player != null && minecraft.gui.screen() == null -> enter(State.SETTLE)
                ticks == OPEN_WORLD_TICKS && minecraft.level == null && minecraft.gui.screen() is TitleScreen && world.isNotEmpty() -> {
                    note("opening the world \"$world\"")
                    minecraft.createWorldOpenFlows().openWorld(world) { }
                }
                ticks > WORLD_TIMEOUT_TICKS -> {
                    note("the world did not load")
                    quit(minecraft)
                }
            }
            State.SETTLE -> if (ticks >= SETTLE_TICKS) {
                if (display.isNotEmpty()) note(moveToDisplay(minecraft, display))
                enter(State.DISPLAY)
            }
            State.DISPLAY -> if (ticks >= DISPLAY_TICKS) {
                val snapshot = BenchEnvironment.snapshot()
                note("window ${snapshot.resolution} on ${snapshot.display}, power ${snapshot.power}")
                startNext(minecraft)
            }
            State.START -> when {
                BenchSuite.isActive || FrameRecorder.isActive -> enter(State.RUNNING)
                ticks >= START_TICKS -> {
                    note("\"${commands[next - 1]}\" did not start a run (see chat in the log)")
                    startNext(minecraft)
                }
            }
            State.RUNNING -> if (!BenchSuite.isActive && !FrameRecorder.isActive) {
                note("\"${commands[next - 1]}\" finished")
                startNext(minecraft)
            }
            State.QUIT -> if (minecraft.level == null && minecraft.gui.screen() is TitleScreen) {
                state = State.IDLE
                minecraft.stop()
            }
            State.IDLE -> Unit
        }
    }

    private fun startNext(minecraft: Minecraft) {
        if (next >= commands.size) {
            note("done")
            quit(minecraft)
            return
        }
        val command = commands[next++]
        note("running \"$command\"")
        val player = minecraft.player
        if (player == null) {
            note("no player, stopping")
            quit(minecraft)
            return
        }
        player.connection.sendUnattendedCommand(command, null)
        enter(State.START)
    }

    private fun quit(minecraft: Minecraft) {
        Files.write(directory.resolve(DONE), log)
        if (minecraft.level != null) minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)
        enter(State.QUIT)
    }

    private fun enter(next: State) {
        state = next
        ticks = 0
    }

    private fun note(line: String) {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        log += "$stamp $line"
        Mobium.LOGGER.info("Mobium bench autorun: {}", line)
    }

    /**
     * Puts the fullscreen window on the display whose name contains [part], in its mode closest to the protocol's
     * 1920×1080 at the desktop refresh rate. The launcher starts the game on whichever display the desktop chooses;
     * SDL moves a fullscreen window to the display of the mode it is given (also on Wayland, which does not let windows
     * position themselves). Vanilla reads the active mode and display back from SDL, so the run records them.
     */
    private fun moveToDisplay(minecraft: Minecraft, part: String): String {
        val handle = minecraft.window.handle()
        val displays = SDLVideo.SDL_GetDisplays() ?: return "no displays: ${SDLError.SDL_GetError()}"
        try {
            var target = 0
            val names = ArrayList<String>()
            for (i in 0 until displays.remaining()) {
                val id = displays.get(i)
                val name = SDLVideo.SDL_GetDisplayName(id) ?: continue
                names += name
                if (target == 0 && name.contains(part, ignoreCase = true)) target = id
            }
            if (target == 0) return "no display named \"$part\" among $names, staying"
            if (SDLVideo.SDL_GetDisplayForWindow(handle) == target && SDLVideo.SDL_GetWindowFullscreenMode(handle) != null) {
                return "already on \"$part\""
            }
            MemoryStack.stackPush().use { stack ->
                val mode = SDL_DisplayMode.malloc(stack)
                val width = BenchEnvironment.PROTOCOL_WIDTH
                val height = BenchEnvironment.PROTOCOL_HEIGHT
                if (!SDLVideo.SDL_GetClosestFullscreenDisplayMode(target, width, height, 0.0f, true, mode)) {
                    return "no ${width}x$height mode on \"$part\": ${SDLError.SDL_GetError()}"
                }
                if (!SDLVideo.SDL_SetWindowFullscreenMode(handle, mode) || !SDLVideo.SDL_SetWindowFullscreen(handle, true)) {
                    return "could not move to \"$part\": ${SDLError.SDL_GetError()}"
                }
                return "moved to \"$part\" (${mode.w()}x${mode.h()}@${mode.refresh_rate()})"
            }
        } finally {
            SDLStdinc.SDL_free(displays)
        }
    }
}
