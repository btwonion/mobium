package dev.nyon.mobium.bench

import dev.nyon.mobium.Mobium
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ClientLevel
import org.lwjgl.sdl.SDLError
import org.lwjgl.sdl.SDLStdinc
import org.lwjgl.sdl.SDLVideo
import org.lwjgl.sdl.SDL_DisplayMode
import org.lwjgl.sdl.SDL_Point
import org.lwjgl.sdl.SDL_Rect
import org.lwjgl.system.MemoryStack
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * An unattended benchmark round (`benchmark/bench_round.py`): when `<game dir>/mobium-bench/autorun` exists at client
 * start, Mobium reads and deletes it, puts the fullscreen window on the monitor whose name contains `display` at once,
 * opens its world (unless the launcher's quick play already did), runs each `command` line in turn as a client command
 * (`mobium suite`, `mobium gatec`, …), waiting for each plan to finish, then writes `autorun-done` next to it and quits
 * the game. The first command waits until the window is on that monitor; the move is repeated until it is, and a window
 * that never gets there stops the autorun before it measures anything. File format, one `key=value` per line: `world=`,
 * `display=` (optional), any number of `command=`. Nothing happens without the file. The log also notes each monitor the
 * window appears on and each time it loses or regains focus or is minimized: vanilla then caps the frame rate
 * (`FrameRecorder` counts those frames per run). Ticked once per client tick on the render thread.
 */
object BenchAutorun {
    private const val REQUEST = "autorun"
    private const val DONE = "autorun-done"
    private const val TICKS_PER_SECOND = 20

    /** How long the title screen may stay before the autorun opens the world itself (the launcher's quick play may). */
    private const val OPEN_WORLD_TICKS = 15 * TICKS_PER_SECOND
    private const val SETTLE_TICKS = 10 * TICKS_PER_SECOND
    private const val DISPLAY_TICKS = 5 * TICKS_PER_SECOND

    /** How long a move gets to arrive before it is repeated, and how many tries the window gets (vanilla-notes §29). */
    private const val MOVE_TICKS = 3 * TICKS_PER_SECOND
    private const val MOVES = 10

    /** Written to `autorun-done` when the window never reached the monitor; `bench_round.py` looks for it. */
    const val NOT_MOVED = "did not reach the monitor"

    /** How long a command may take to start a plan before it counts as refused (its reason is in chat and the log). */
    private const val START_TICKS = 3 * TICKS_PER_SECOND
    private const val WORLD_TIMEOUT_TICKS = 300 * TICKS_PER_SECOND

    private enum class State { IDLE, WORLD, SETTLE, DISPLAY, START, RUNNING, QUIT }

    private var state = State.IDLE
    private var ticks = 0
    private lateinit var directory: Path
    private var world = ""
    private var display = ""

    /** SDL id of the monitor named by `display`; 0 when none is named or none has that name. */
    private var target = 0
    private var moves = 0
    private var sinceMove = 0
    private var shownX = Int.MIN_VALUE
    private var shownY = Int.MIN_VALUE
    private val commands = ArrayList<String>()
    private var next = 0
    private val log = ArrayList<String>()
    private var focused = true
    private var minimized = false

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
        // The game opens on whichever display the desktop chooses; moving before the world loads keeps the round from
        // looking as if it ran on the wrong one. Each tick checks where the window is and repeats a move that did not
        // arrive; the first command waits for it.
        if (display.isNotEmpty()) target = findDisplay(display)
        if (target != 0) move(minecraft)
        focused = minecraft.window.isFocused
        minimized = minecraft.window.isIconified
        state = State.WORLD
        ticks = 0
    }

    @JvmStatic
    fun tick(minecraft: Minecraft) {
        if (state == State.IDLE) return
        ticks++
        noteFocus(minecraft)
        if (target != 0) keepOnTarget(minecraft)
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
            State.SETTLE -> if (ticks >= SETTLE_TICKS) enter(State.DISPLAY)
            State.DISPLAY -> when {
                ticks < DISPLAY_TICKS -> Unit
                target == 0 || onTarget(minecraft) -> {
                    val snapshot = BenchEnvironment.snapshot()
                    note("window ${snapshot.resolution} on ${snapshot.display}, power ${snapshot.power}")
                    startNext(minecraft)
                }
                moves >= MOVES && sinceMove >= MOVE_TICKS -> {
                    note("the window $NOT_MOVED \"$display\" after $moves moves (it is on ${shownOn(minecraft)}); stopping")
                    quit(minecraft)
                }
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

    /** Notes when the window loses or regains focus or is minimized: vanilla caps exclusive fullscreen then. */
    private fun noteFocus(minecraft: Minecraft) {
        val window = minecraft.window
        if (window.isFocused == focused && window.isIconified == minimized) return
        focused = window.isFocused
        minimized = window.isIconified
        note(
            when {
                minimized -> "window minimized"
                focused -> "window focused"
                else -> "window lost focus"
            }
        )
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

    /** The SDL id of the first display whose name contains [part]; 0 (and a note) when there is none. */
    private fun findDisplay(part: String): Int {
        val displays = SDLVideo.SDL_GetDisplays() ?: return 0.also { note("no displays: ${SDLError.SDL_GetError()}") }
        try {
            val names = ArrayList<String>()
            for (i in 0 until displays.remaining()) {
                val id = displays.get(i)
                val name = SDLVideo.SDL_GetDisplayName(id) ?: continue
                if (name.contains(part, ignoreCase = true)) return id
                names += name
            }
            note("no display named \"$part\" among $names, staying")
            return 0
        } finally {
            SDLStdinc.SDL_free(displays)
        }
    }

    /**
     * Repeats the move while the window is not on [target], each time [MOVE_TICKS] after the last, at most [MOVES]
     * times; notes each monitor the window appears on.
     */
    private fun keepOnTarget(minecraft: Minecraft) {
        sinceMove++
        val window = minecraft.window
        if (window.x != shownX || window.y != shownY) {
            shownX = window.x
            shownY = window.y
            note("window shown on ${shownOn(minecraft)}")
        }
        if (state == State.WORLD || state == State.SETTLE || state == State.DISPLAY) {
            if (moves < MOVES && sinceMove >= MOVE_TICKS && !onTarget(minecraft)) move(minecraft)
        }
    }

    /**
     * Whether the compositor shows the window on [target] in a fullscreen mode. SDL answers every "which display" query
     * for a fullscreen window with the display of the mode it was given (`SDL_GetDisplayForWindow`, and vanilla's
     * `findBestMonitor` with it), whether or not the window got there; the move events vanilla keeps in `Window.x/y`
     * carry the origin of the monitor the window really is on (vanilla-notes §29).
     */
    private fun onTarget(minecraft: Minecraft): Boolean {
        val window = minecraft.window
        if (SDLVideo.SDL_GetWindowFullscreenMode(window.handle()) == null) return false
        MemoryStack.stackPush().use { stack ->
            val bounds = SDL_Rect.malloc(stack)
            return SDLVideo.SDL_GetDisplayBounds(target, bounds) && window.x == bounds.x() && window.y == bounds.y()
        }
    }

    /** The name of the monitor the window's last move event placed it on. */
    private fun shownOn(minecraft: Minecraft): String {
        val window = minecraft.window
        MemoryStack.stackPush().use { stack ->
            val id = SDLVideo.SDL_GetDisplayForPoint(SDL_Point.malloc(stack).set(window.x, window.y))
            val name = if (id == 0) "no display" else SDLVideo.SDL_GetDisplayName(id) ?: "display $id"
            return "\"$name\" (${window.x}, ${window.y})"
        }
    }

    /**
     * Asks SDL to put the fullscreen window on [target], in its mode closest to the protocol's 1920×1080 at the desktop
     * refresh rate. The launcher starts the game on whichever display the desktop chooses; SDL moves a fullscreen window
     * to the display of the mode it is given (also on Wayland, which does not let windows position themselves). The
     * compositor applies the move later, or drops it (vanilla-notes §29): [keepOnTarget] checks and repeats it. Vanilla
     * reads the active mode and display back from SDL, so the run records them.
     */
    private fun move(minecraft: Minecraft) {
        moves++
        sinceMove = 0
        val handle = minecraft.window.handle()
        MemoryStack.stackPush().use { stack ->
            val mode = SDL_DisplayMode.malloc(stack)
            val width = BenchEnvironment.PROTOCOL_WIDTH
            val height = BenchEnvironment.PROTOCOL_HEIGHT
            if (!SDLVideo.SDL_GetClosestFullscreenDisplayMode(target, width, height, 0.0f, true, mode)) {
                note("no ${width}x$height mode on \"$display\": ${SDLError.SDL_GetError()}")
            } else if (!SDLVideo.SDL_SetWindowFullscreenMode(handle, mode) || !SDLVideo.SDL_SetWindowFullscreen(handle, true)) {
                note("could not move to \"$display\": ${SDLError.SDL_GetError()}")
            } else {
                note("moving to \"$display\" (${mode.w()}x${mode.h()}@${mode.refresh_rate()}), try $moves")
            }
        }
    }
}
