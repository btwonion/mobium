package dev.nyon.mobium.debug.selfcheck

import com.mojang.blaze3d.platform.NativeImage
import dev.nyon.konfig.config.loadConfig
import dev.nyon.mobium.Mobium
import dev.nyon.mobium.MobiumKeys
import dev.nyon.mobium.bench.BenchSuite
import dev.nyon.mobium.bench.SceneBuilder
import dev.nyon.mobium.cache.ArmorStands
import dev.nyon.mobium.cache.Displays
import dev.nyon.mobium.cache.EntityPolicy
import dev.nyon.mobium.cache.EntityStateSource
import dev.nyon.mobium.cache.ItemFrames
import dev.nyon.mobium.cache.Lids
import dev.nyon.mobium.cache.Pots
import dev.nyon.mobium.cache.Shelves
import dev.nyon.mobium.cache.Signs
import dev.nyon.mobium.cache.Skulls
import dev.nyon.mobium.cache.StaticCache
import dev.nyon.mobium.config.CacheConfig
import dev.nyon.mobium.config.Config
import dev.nyon.mobium.config.applyConfig
import dev.nyon.mobium.config.config
import dev.nyon.mobium.config.screen.MobiumSettingsScreen
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.CompatMod
import dev.nyon.mobium.core.Fallback
import dev.nyon.mobium.core.IrisCompat
import dev.nyon.mobium.core.Module
import dev.nyon.mobium.core.ModuleStates
import dev.nyon.mobium.core.OffReason
import dev.nyon.mobium.debug.AbToggle
import dev.nyon.mobium.debug.ModuleLines
import dev.nyon.mobium.debug.Sections
import dev.nyon.mobium.debug.Validation
import jdk.jfr.Configuration
import jdk.jfr.Recording
import net.minecraft.client.CloudStatus
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus
import net.minecraft.client.gui.components.events.ContainerEventHandler
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.client.gui.components.toasts.ToastManager
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ParticleStatus
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale

/*? if fabric {*/
import dev.nyon.mobium.config.ModMenuImpl
/*?} else {*/
/*import net.minecraft.client.gui.components.AbstractSelectionList
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.network.chat.contents.TranslatableContents
import net.neoforged.neoforge.client.gui.modlist.ModListScreen
*//*?}*/

/**
 * Dev-only self-check that walks through a PR's *How to verify* inside the running game, so the same scenario runs on
 * both loaders without anyone at the keyboard. Started by the file `<game dir>/mobium-selfcheck/request` (deleted
 * when read) in a development environment only; writes `report.txt` and screenshots to `<game dir>/mobium-selfcheck/`
 * and closes the game when done.
 *
 * The M4 scenario: the settings screen opened the loader's way (Mod Menu's factory on Fabric, the NeoForge mod list's
 * Config button), in English and German without untranslated keys; Mobium's Sodium page and its Apply path; and in
 * the benchmark world's `storage_room` the A/B key, F3 `mobium:modules` and `/mobium debug fail` with pixel
 * comparisons against the vanilla picture. The world "Mobium Bench" has to be in `saves/`.
 */
object SelfCheck {
    private const val WORLD = "Mobium Bench"
    private const val SCENE = "storage_room"
    private const val TICKS_PER_SECOND = 20
    private const val GALLERY = "cache_gallery"
    private const val IRIS_WITHOUT_MOBIUM = "iris-without-mobium"
    private const val PROFILE = "profile"
    private const val PROFILE_MOBS = "profile-mobs"
    private const val MOB_SCENE = "mob_pen_live"
    private const val GATE_B = "gateb"
    private const val GALLERY_ONLY = "gallery"
    private const val CHESTS = 1000
    private const val SIGNS = 300

    /**
     * Cached entities in the gallery (benchmark/generate.py): 6 of 7 item frames (the map stays vanilla), 3 of 6 armor
     * stands (armor, name tag, glowing stay vanilla), 4 of 6 displays (billboard, stained glass).
     */
    private const val GALLERY_ENTITIES = "6 3 4"

    // Must match X0 / Z0 / GROUND in benchmark/generate.py.
    private const val X0 = -3840
    private const val Z0 = -2816
    private const val GROUND = -60

    /** A made-up entity id for the breaking overlay, so that the server sends it to the player. */
    private const val BREAKER_ID = 0x7FFF0000

    private var steps: Iterator<Wait>? = null
    private var current: Wait? = null
    private lateinit var directory: Path
    private val results = ArrayList<String>()
    private var failures = 0
    private var restoreOptions: (() -> Unit)? = null
    private var renderDistanceBefore = 12

    /** One thing the scenario waits for; checked once per client tick. A timeout fails the check if [required]. */
    private class Wait(val description: String, val timeoutTicks: Int, val required: Boolean, val condition: () -> Boolean) {
        var waited = 0
    }

    /** Called once on the render thread when the client has started. */
    fun startIfRequested(minecraft: Minecraft) {
        if (!Compat.isDevelopment) return
        val directory = minecraft.gameDirectory.toPath().resolve("mobium-selfcheck")
        val request = directory.resolve("request")
        if (!Files.exists(request)) return
        val mode = Files.readString(request).trim()
        Files.delete(request)
        this.directory = directory
        Files.createDirectories(directory.resolve("screenshots"))
        Mobium.LOGGER.info("Mobium self-check started ({} on {}{})", Compat.LOADER, minecraft.gameDirectory, if (mode.isEmpty()) "" else ", $mode")
        steps = when (mode) {
            IRIS_WITHOUT_MOBIUM -> irisWithoutMobium(minecraft)
            PROFILE -> profile(minecraft)
            PROFILE_MOBS -> profileMobs(minecraft)
            GATE_B -> gateB(minecraft)
            GALLERY_ONLY -> galleryOnly(minecraft)
            else -> scenario(minecraft)
        }.iterator()
    }

    /** Advances the scenario; called at the end of every client tick. */
    fun tick() {
        val iterator = steps ?: return
        // Nobody touches the game during the self-check; without this vanilla caps the frame rate after 60 s without
        // input (FramerateLimitTracker), and waits counted in frames (the cache's pauses, its sweep) take far longer.
        Minecraft.getInstance().framerateLimitTracker.onInputReceived()
        val wait = current
        if (wait != null) {
            if (!wait.condition()) {
                if (++wait.waited < wait.timeoutTicks) return
                if (wait.required) check("wait: ${wait.description}", false, "not reached after ${wait.timeoutTicks / TICKS_PER_SECOND} s")
            }
            current = null
        }
        try {
            if (iterator.hasNext()) {
                current = iterator.next()
            } else {
                steps = null
            }
        } catch (e: Exception) {
            Mobium.LOGGER.error("Mobium self-check aborted", e)
            check("scenario", false, "aborted: $e")
            steps = null
            finish(Minecraft.getInstance())
        }
    }

    private fun ticks(count: Int) = Wait("$count ticks", count, required = false) { false }

    private fun until(description: String, seconds: Int, condition: () -> Boolean) =
        Wait(description, seconds * TICKS_PER_SECOND, required = true, condition)

    /** Like [until], but a timeout is no failure of its own: a check with details follows. */
    private fun upTo(description: String, seconds: Int, condition: () -> Boolean) =
        Wait(description, seconds * TICKS_PER_SECOND, required = false, condition)

    private fun check(name: String, passed: Boolean, detail: String = "") {
        if (!passed) failures++
        val line = "${if (passed) "PASS" else "FAIL"} $name${if (detail.isEmpty()) "" else ": $detail"}"
        results += line
        Mobium.LOGGER.info("Mobium self-check {}", line)
    }

    /**
     * Remembers the game options the scenario changes and restores and saves them when it ends, so the dev game
     * directory keeps the player's settings.
     */
    private fun snapshotOptions(minecraft: Minecraft) {
        val options = minecraft.options
        val pause = options.pauseOnLostFocus
        val particles = options.particles().get()
        val framerateLimit = options.framerateLimit().get()
        val vsync = options.enableVsync().get()
        val onboarding = options.onboardAccessibility
        val clouds = options.cloudStatus().get()
        val preset = options.graphicsPreset().get()
        val language = options.languageCode
        renderDistanceBefore = options.renderDistance().get()
        // A window that loses focus would otherwise open the pause menu in the middle of the world checks.
        options.pauseOnLostFocus = false
        restoreOptions = {
            options.pauseOnLostFocus = pause
            options.onboardAccessibility = onboarding
            options.cloudStatus().set(clouds)
            options.particles().set(particles)
            options.framerateLimit().set(framerateLimit)
            options.enableVsync().set(vsync)
            options.renderDistance().set(renderDistanceBefore)
            // Changing the clouds or the render distance turns the preset to "custom"; setting the old preset again
            // restores it.
            options.graphicsPreset().set(preset)
            if (options.languageCode != language) {
                minecraft.languageManager.setSelected(language)
                options.languageCode = language
            }
            options.save()
        }
    }

    private fun scenario(minecraft: Minecraft) = sequence {
        snapshotOptions(minecraft)
        titleScreen(minecraft)
        val saved = config.deepCopy()
        applyConfig(Config())
        check("startup state", ModuleStates.isActive(Module.STATIC_CACHE), ModuleStates.status(Module.STATIC_CACHE).describe())

        openSettingsThroughLoader(minecraft)
        settingsPages(minecraft, "en")

        setLanguage(minecraft, "de_de")
        minecraft.gui.setScreen(MobiumSettingsScreen(TitleScreen()))
        settingsPages(minecraft, "de")
        val german = widgetTexts(minecraft.gui.screen())
        check("German is shown", german.any { "Allgemein" in it } && german.any { "Statischer Cache" in it }, german.take(6).joinToString(" | "))
        setLanguage(minecraft, "en_us")

        if (Compat.isLoaded(CompatMod.SODIUM)) sodiumPage(minecraft)

        minecraft.gui.setScreen(TitleScreen())
        yield(ticks(5))
        persistence()

        if (Files.isDirectory(minecraft.gameDirectory.toPath().resolve("saves").resolve(WORLD))) {
            inWorld(minecraft)
        } else {
            check("world", false, "saves/$WORLD is missing; copy versions/26.3-fabric/build/bench-world/$WORLD")
        }

        applyConfig(saved)
        if (minecraft.level != null) {
            // What the pause menu's "Save and Quit to Title" does.
            minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)
            yield(until("back on the title screen", 120) { minecraft.level == null && minecraft.gui.screen() is TitleScreen })
        }
        finish(minecraft)
    }

    /** `--mode gallery`: only the world part's gallery checks (cached types, effects, packs), for quick iterations. */
    private fun galleryOnly(minecraft: Minecraft) = sequence {
        snapshotOptions(minecraft)
        titleScreen(minecraft)
        val saved = config.deepCopy()
        applyConfig(Config())
        inWorld(minecraft, galleryOnly = true)
        applyConfig(saved)
        if (minecraft.level != null) {
            minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)
            yield(until("back on the title screen", 120) { minecraft.level == null && minecraft.gui.screen() is TitleScreen })
        }
        finish(minecraft)
    }

    /** Waits for the title screen, past the loader's warning screen and the first-start accessibility screen. */
    private suspend fun SequenceScope<Wait>.titleScreen(minecraft: Minecraft) {
        var handled: Screen? = null
        yield(until("title screen", 120) {
            val screen = minecraft.gui.screen()
            // Each start screen is handled once; closing it takes a few ticks.
            if (screen != null && screen !== handled) {
                if (screen.javaClass.simpleName == "LoadingErrorScreen") {
                    handled = screen
                    continueAfterLoadingWarnings(screen)
                } else if (screen is AccessibilityOnboardingScreen) {
                    handled = screen
                    results += "NOTE the first-start accessibility screen was skipped (restored afterwards)"
                    minecraft.options.onboardAccessibility = false
                    screen.onClose()
                }
            }
            screen is TitleScreen
        })
        yield(ticks(40))
        results += "NOTE the A/B key reads \"${AbToggle.keyName().string}\" on this keyboard layout"
    }

    /**
     * `--mode iris-without-mobium`: Mobium switched off, the gallery with the first shader pack for 20 seconds. Tells
     * whether a crash with a shader pack happens without Mobium too.
     */
    private fun irisWithoutMobium(minecraft: Minecraft) = sequence {
        snapshotOptions(minecraft)
        titleScreen(minecraft)
        val saved = config.deepCopy()
        applyConfig(Config(enabled = false))
        minecraft.createWorldOpenFlows().openWorld(WORLD) { }
        yield(until("world loaded", 180) { minecraft.level != null && minecraft.player != null && minecraft.gui.screen() == null })
        yield(ticks(40))
        if (buildScene(minecraft, GALLERY) && Compat.isLoaded(CompatMod.IRIS)) {
            val pack = IrisCheck.firstPack()
            if (pack == null) {
                check("shader pack present", false, "no zip in shaderpacks/")
            } else {
                val restore = IrisCheck.select(pack, mapOf("TAA_DEFINE" to "0", "CLOUD_SPEED_MULT" to "0"))
                yield(until("shader pack $pack in use", 120) { IrisCompat.isShaderPackInUse() })
                yield(ticks(20 * TICKS_PER_SECOND))
                check("shader pack $pack rendered for 20 s with Mobium off", !StaticCache.active, ModuleStates.status(Module.STATIC_CACHE).describe())
                screenshot(minecraft, "iris-without-mobium")
                yield(until("screenshot iris-without-mobium", 10) { shotDone })
                restore()
                yield(until("shader pack off", 120) { !IrisCompat.isShaderPackInUse() })
            }
        }
        applyConfig(saved)
        minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)
        yield(until("back on the title screen", 120) { minecraft.level == null && minecraft.gui.screen() is TitleScreen })
        finish(minecraft)
    }

    /**
     * `--mode gateb`: `/mobium gateb 20 2` in the dev client's window (not the protocol's 1920×1080 or Anton's
     * hardware, so only a hint), including its run with the cache's draw calls skipped; the medians file is copied into
     * `mobium-selfcheck/`. Then `display_build` and `map_wall` with the cache off and on, 15 s each, twice.
     */
    private fun gateB(minecraft: Minecraft) = sequence {
        snapshotOptions(minecraft)
        titleScreen(minecraft)
        val saved = config.deepCopy()
        applyConfig(Config())
        minecraft.options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF)
        minecraft.options.enableVsync().set(false)
        minecraft.createWorldOpenFlows().openWorld(WORLD) { }
        yield(until("world loaded", 180) { minecraft.level != null && minecraft.player != null && minecraft.gui.screen() == null })
        yield(ticks(40))
        val bench = minecraft.gameDirectory.toPath().resolve("mobium-bench")
        val before = if (Files.isDirectory(bench)) Files.list(bench).use { it.toList() }.toSet() else emptySet()
        checkNotNull(minecraft.player).connection.sendUnattendedCommand("mobium gateb 20 2", null)
        yield(ticks(TICKS_PER_SECOND))
        yield(until("Gate B plan finished", 900) { !BenchSuite.isActive })
        val file = Files.list(bench).use { files -> files.filter { it !in before && it.fileName.toString().startsWith("gateb-") }.findFirst().orElse(null) }
        if (file == null) {
            check("gateb medians written", false)
        } else {
            Files.copy(file, directory.resolve(file.fileName))
            val lines = Files.readAllLines(file)
            val window = minecraft.window
            results += "NOTE window ${window.width}x${window.height}: " + lines.filter { it.startsWith("#") }.joinToString("; ")
            for (line in lines.drop(1).filter { !it.startsWith("#") }) results += "NOTE " + line.split(',').take(4).joinToString(" ")
            check("gateb medians written", true, file.toString())
        }
        // First look at the entity scenes: the static cache off and on, alternating, 15 s each.
        for (scene in listOf("display_build", "map_wall")) {
            if (!buildScene(minecraft, scene)) continue
            for (cache in listOf(false, true, false, true)) {
                config.cache.enabled = cache
                ModuleStates.refresh("self-check")
                yield(ticks(3 * TICKS_PER_SECOND))
                val summary = bench.resolve("summary.csv")
                // The last row rather than the row count: a summary.csv with older columns is renamed and starts over.
                val lastBefore = if (Files.exists(summary)) Files.readAllLines(summary).lastOrNull() else null
                checkNotNull(minecraft.player).connection.sendUnattendedCommand("mobium bench $scene 15", null)
                yield(until("bench $scene (cache $cache)", 60) {
                    Files.exists(summary) && Files.readAllLines(summary).let { it.size > 1 && it.last() != lastBefore }
                })
                val row = Files.readAllLines(summary).let { lines -> lines.first().split(',').zip(lines.last().split(',')).toMap() }
                results += "NOTE $scene, cache ${if (cache) "on" else "off"}: ${row["avg_ms"]} ms, extract " +
                    "${row["extract_entities_ms"]}, submit ${row["submit_entities_ms"]}, prepare ${row["prepare_ms"]}; " +
                    StaticCache.debugLine()
            }
        }
        applyConfig(saved)
        minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)
        yield(until("back on the title screen", 120) { minecraft.level == null && minecraft.gui.screen() is TitleScreen })
        finish(minecraft)
    }

    /**
     * `--mode profile`: `storage_room` recorded with Java Flight Recorder (method samples every millisecond), 20 s with
     * the static cache and 20 s with Mobium off, to `mobium-selfcheck/storage_room-{cache,vanilla}.jfr`.
     */
    private fun profile(minecraft: Minecraft) = sequence {
        snapshotOptions(minecraft)
        titleScreen(minecraft)
        val saved = config.deepCopy()
        applyConfig(Config())
        minecraft.options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF)
        minecraft.options.enableVsync().set(false)
        minecraft.createWorldOpenFlows().openWorld(WORLD) { }
        yield(until("world loaded", 180) { minecraft.level != null && minecraft.player != null && minecraft.gui.screen() == null })
        yield(ticks(40))
        minecraft.options.cloudStatus().set(CloudStatus.OFF)
        if (!minecraft.gui.hud.isHidden) minecraft.gui.hud.toggle()
        if (buildScene(minecraft, SCENE)) {
            for ((name, mobium) in listOf("cache" to true, "vanilla" to false)) {
                if (!mobium) applyConfig(Config(enabled = false))
                yield(ticks(5 * TICKS_PER_SECOND))
                val recording = Recording(Configuration.getConfiguration("profile"))
                recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1))
                val before = StaticCache.totals.copyOf()
                val framesBefore = Sections.frame
                recording.start()
                yield(ticks(20 * TICKS_PER_SECOND))
                recording.stop()
                val totals = LongArray(3) { StaticCache.totals[it] - before[it] }
                results += "NOTE $name: ${Sections.frame - framesBefore} frames, ${totals[0]} fast, ${totals[1]} full checks, " +
                    "${totals[2]} light updates"
                val file = directory.resolve("$SCENE-$name.jfr")
                recording.dump(file)
                recording.close()
                check("recorded $file", Files.size(file) > 0, StaticCache.debugLine())
            }
        }
        applyConfig(saved)
        minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)
        yield(until("back on the title screen", 120) { minecraft.level == null && minecraft.gui.screen() is TitleScreen })
        finish(minecraft)
    }

    /**
     * `--mode profile-mobs`: `mob_pen_live` with vanilla models (no packs; the dev client still has EMF, ETF and Iris),
     * with Fresh Animations (setup C), then also with the first shader pack (setup D), each as drawn and with the
     * models' vertex writes skipped ([recordMobs]); one JFR recording per setup goes to
     * `mobium-selfcheck/mob_pen_live-{plain,c,d}.jfr`. The measurement basis for Module 2: how much of the model
     * tessellation is the vertex work it replaces.
     */
    private fun profileMobs(minecraft: Minecraft) = sequence {
        snapshotOptions(minecraft)
        titleScreen(minecraft)
        val saved = config.deepCopy()
        applyConfig(Config())
        minecraft.options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF)
        minecraft.options.enableVsync().set(false)
        minecraft.createWorldOpenFlows().openWorld(WORLD) { }
        yield(until("world loaded", 180) { minecraft.level != null && minecraft.player != null && minecraft.gui.screen() == null })
        yield(ticks(40))
        minecraft.options.cloudStatus().set(CloudStatus.OFF)
        if (!minecraft.gui.hud.isHidden) minecraft.gui.hud.toggle()
        val repository = minecraft.resourcePackRepository
        repository.reload()
        val fresh = repository.availableIds.firstOrNull { "fresh" in it.lowercase(Locale.ROOT) }
        if (fresh == null) results += "NOTE no Fresh Animations zip in resourcepacks/, setup C runs without it"
        if (buildScene(minecraft, MOB_SCENE)) {
            recordMobs(minecraft, "plain")
            if (fresh != null) {
                repository.addPack(fresh)
                minecraft.options.updateResourcePacks(repository)
                yield(ticks(10))
                yield(until("resource pack $fresh loaded", 120) { minecraft.gui.overlay() == null })
            }
            recordMobs(minecraft, "c")
            val pack = if (Compat.isLoaded(CompatMod.IRIS)) IrisCheck.firstPack() else null
            if (pack == null) {
                results += "NOTE no shader pack, setup D skipped"
            } else {
                val restore = IrisCheck.select(pack, emptyMap())
                yield(until("shader pack $pack in use", 120) { IrisCompat.isShaderPackInUse() })
                recordMobs(minecraft, "d")
                restore()
                yield(until("shader pack off", 120) { !IrisCompat.isShaderPackInUse() })
            }
            if (fresh != null) {
                repository.removePack(fresh)
                minecraft.options.updateResourcePacks(repository)
                yield(ticks(10))
                yield(until("resource pack $fresh removed", 120) { minecraft.gui.overlay() == null })
            }
        }
        applyConfig(saved)
        minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)
        yield(until("back on the title screen", 120) { minecraft.level == null && minecraft.gui.screen() is TitleScreen })
        finish(minecraft)
    }

    /**
     * [MOB_SCENE] for 4 × 8 s, alternating as drawn and with [Sections.skipModelVertices] (the models write no
     * vertices, so they vanish), with the stage timers sampled once a second (each a 64-frame average). The difference
     * is what the vertex work costs, the most Module 2 can take off the CPU. The first as-drawn run is recorded with
     * Java Flight Recorder (method samples every millisecond).
     */
    private suspend fun SequenceScope<Wait>.recordMobs(minecraft: Minecraft, setup: String) {
        yield(ticks(10 * TICKS_PER_SECOND))
        Sections.forceTimers = true
        val sums = Array(2) { DoubleArray(Sections.COUNT) }
        val frameMs = DoubleArray(2)
        for (round in 0 until 4) {
            val skip = round % 2 == 1
            Sections.skipModelVertices = skip
            yield(ticks(2 * TICKS_PER_SECOND))
            val recording = if (round == 0) Recording(Configuration.getConfiguration("profile")) else null
            recording?.enable("jdk.ExecutionSample")?.withPeriod(Duration.ofMillis(1))
            val framesBefore = Sections.frame
            val start = System.nanoTime()
            recording?.start()
            repeat(8) {
                yield(ticks(TICKS_PER_SECOND))
                for (i in 0 until Sections.COUNT) sums[if (skip) 1 else 0][i] += Sections.averageMillis(i) / 16
            }
            recording?.stop()
            frameMs[if (skip) 1 else 0] += (System.nanoTime() - start) / 1e6 / (Sections.frame - framesBefore) / 2
            if (recording != null) {
                val file = directory.resolve("$MOB_SCENE-$setup.jfr")
                recording.dump(file)
                recording.close()
                check("recorded $file", Files.size(file) > 0)
            }
        }
        Sections.skipModelVertices = false
        Sections.forceTimers = false
        val window = minecraft.window
        for ((index, name) in listOf("drawn", "no vertices").withIndex()) {
            results += String.format(Locale.ROOT, "NOTE setup %s, %s, window %dx%d: frame %.2f ms; ", setup, name, window.width, window.height, frameMs[index]) +
                Sections.NAMES.indices.filter { sums[index][it] > 0.0005 }
                    .joinToString { String.format(Locale.ROOT, "%s %.3f", Sections.NAMES[it], sums[index][it]) }
        }
    }

    /**
     * NeoForge stops on "Mods loaded with warnings" until someone presses "Proceed to main menu"; this presses it
     * (through the screen's private task) and notes it in the report. The warnings themselves are in the log.
     */
    private fun continueAfterLoadingWarnings(screen: Screen) {
        val task = screen.javaClass.getDeclaredField("nextScreenTask").apply { isAccessible = true }.get(screen) as Runnable
        results += "NOTE the loader showed mod loading warnings (see the log) and the check continued"
        task.run()
    }

    // region settings

    private suspend fun SequenceScope<Wait>.openSettingsThroughLoader(minecraft: Minecraft) {
        val title = minecraft.gui.screen() ?: TitleScreen()
        /*? if fabric {*/
        minecraft.gui.setScreen(ModMenuImpl().modConfigScreenFactory.create(title))
        yield(ticks(5))
        check("Mod Menu opens the settings", minecraft.gui.screen() is MobiumSettingsScreen, "${minecraft.gui.screen()}")
        /*?} else {*/
        /*val modList = ModListScreen.create(title)
        minecraft.gui.setScreen(modList)
        yield(ticks(10))
        widgets(modList).filterIsInstance<EditBox>().firstOrNull()?.value = "mobium"
        yield(ticks(5))
        val list = widgets(modList).filterIsInstance<AbstractSelectionList<*>>().firstOrNull()
        val entry = list?.children()?.firstOrNull()
        if (list != null && entry != null) {
            // Entry is protected; select through the public setSelected(E), as a click on the row does.
            AbstractSelectionList::class.java.methods.first { it.name == "setSelected" && it.parameterCount == 1 }.invoke(list, entry)
        }
        yield(ticks(5))
        screenshot(minecraft, "neoforge-mod-list")
        yield(until("screenshot neoforge-mod-list", 10) { shotDone })
        val configButton = widgets(modList).filterIsInstance<Button>().firstOrNull {
            (it.message.contents as? TranslatableContents)?.key == "neoforge.screen.mods.button.config"
        }
        check("mod list has an active Config button for Mobium", configButton?.active == true, "button: $configButton, entry: $entry")
        configButton?.onPress(MouseButtonInfo(0, 0))
        yield(ticks(5))
        check("the Config button opens the settings", minecraft.gui.screen() is MobiumSettingsScreen, "${minecraft.gui.screen()}")
        *//*?}*/
    }

    /** Screenshots of the whole list and a check that no visible text is an untranslated key. */
    private suspend fun SequenceScope<Wait>.settingsPages(minecraft: Minecraft, language: String) {
        val screen = minecraft.gui.screen()
        if (screen !is MobiumSettingsScreen) {
            check("settings screen ($language)", false, "open screen is $screen")
            return
        }
        val texts = ArrayList<String>()
        for (page in 0..3) {
            yield(ticks(5))
            texts += widgetTexts(screen)
            screenshot(minecraft, "settings-$language-$page")
            yield(until("screenshot settings-$language-$page", 10) { shotDone })
            screen.mouseScrolled(screen.width / 2.0, screen.height / 2.0, 0.0, -6.0)
        }
        val raw = texts.filter { "mobium." in it || "key.mobium" in it }
        check("settings texts translated ($language)", raw.isEmpty() && texts.size > 20, "${texts.size} texts, raw keys: $raw")
        check(
            "status lines ($language)",
            Module.entries.all { module -> texts.any { it.startsWith(Component.translatable(module.translationKey).string + ":") } },
            texts.take(4).joinToString(" | ")
        )
    }

    private suspend fun SequenceScope<Wait>.setLanguage(minecraft: Minecraft, code: String) {
        minecraft.languageManager.setSelected(code)
        minecraft.options.languageCode = code
        val reload = minecraft.reloadResourcePacks()
        yield(until("language $code loaded", 120) { reload.isDone && minecraft.gui.overlay() == null })
        yield(ticks(5))
    }

    private suspend fun SequenceScope<Wait>.sodiumPage(minecraft: Minecraft) {
        val page = SodiumCheck.mobiumPage()
        check("Sodium lists Mobium's page", page != null, SodiumCheck.describe())
        if (page == null) return
        minecraft.gui.setScreen(SodiumCheck.screen(TitleScreen(), page))
        yield(ticks(10))
        screenshot(minecraft, "sodium-page")
        yield(until("screenshot sodium-page", 10) { shotDone })
        check("Sodium option names translated", SodiumCheck.optionNames().none { "mobium." in it }, SodiumCheck.optionNames().joinToString(" | "))
        check("Sodium page has the module status button", SodiumCheck.hasStatusButton())

        // Apply through Sodium's own path, as its Apply button does: the binding writes the config, the storage
        // handler saves it and re-resolves the modules.
        SodiumCheck.setAndApply(Identifier.fromNamespaceAndPath(Mobium.MOD_ID, "enabled"), false)
        check(
            "Sodium Apply switches Mobium off",
            !config.enabled && ModuleStates.status(Module.STATIC_CACHE).reason == OffReason.GLOBAL_SWITCH && !savedConfig().enabled,
            ModuleStates.status(Module.STATIC_CACHE).describe()
        )
        SodiumCheck.setAndApply(Identifier.fromNamespaceAndPath(Mobium.MOD_ID, "enabled"), true)
        check("Sodium Apply switches Mobium on", config.enabled && ModuleStates.isActive(Module.STATIC_CACHE) && savedConfig().enabled)
    }

    /** What the settings screens saved is what the next start loads. */
    private fun persistence() {
        val changed = config.deepCopy().also { it.cache.memoryBudgetMb = 256; it.cache.types.signs = false }
        applyConfig(changed)
        val loaded = savedConfig()
        check("settings are saved", loaded == changed, "loaded: $loaded")
        applyConfig(changed.deepCopy().also { it.cache.memoryBudgetMb = CacheConfig.DEFAULT_MEMORY_BUDGET_MB; it.cache.types.signs = true })
    }

    private fun savedConfig(): Config = loadConfig<Config>()

    // endregion

    // region world

    private suspend fun SequenceScope<Wait>.inWorld(minecraft: Minecraft, galleryOnly: Boolean = false) {
        minecraft.createWorldOpenFlows().openWorld(WORLD) { }
        yield(until("world loaded", 180) { minecraft.level != null && minecraft.player != null && minecraft.gui.screen() == null })
        yield(ticks(40))
        // Drifting clouds move in every scene; without them two pictures of the same frame state match. Restored with
        // the other options at the end.
        minecraft.options.cloudStatus().set(CloudStatus.OFF)
        // Particles (the ender chest's) move between two pictures; restored at the end too.
        minecraft.options.particles().set(ParticleStatus.MINIMAL)
        // With vsync each swap waits for the compositor, which throttles a window behind others (Wayland: ~17 FPS), and
        // waits counted in frames (the cache's pauses, its sweep) and same-tick picture pairs need the frame rate.
        minecraft.options.enableVsync().set(false)
        val hud = minecraft.gui.hud
        if (!hud.isHidden) hud.toggle()

        if (!buildScene(minecraft, GALLERY)) return
        gallery(minecraft)
        if (galleryOnly) return

        if (!buildScene(minecraft, SCENE)) return
        minecraft.gui.hud.chat.clearMessages(false)
        yield(ticks(TICKS_PER_SECOND))

        check("static cache active", StaticCache.active && ModuleStates.isActive(Module.STATIC_CACHE), ModuleStates.status(Module.STATIC_CACHE).describe())
        check(
            "chests and signs cached",
            StaticCache.cachedCount(Lids.key) == CHESTS && StaticCache.cachedCount(Signs.key) == SIGNS,
            StaticCache.debugLine() + if (StaticCache.cachedCount(Lids.key) == CHESTS) "" else "; " + StaticCache.describeEntries(Pots.key, 2)
        )
        capture(minecraft)
        yield(until("capture Mobium picture", 10) { captured != null })
        val mobiumImage = takeCapture("capture-mobium")

        // A/B key: pressed through its key mapping, like the keyboard does.
        KeyMapping.click(MobiumKeys.AB_TOGGLE.defaultKey)
        yield(ticks(TICKS_PER_SECOND))
        check(
            "A/B key switches to vanilla",
            AbToggle.mobiumOff && !StaticCache.active && ModuleStates.status(Module.STATIC_CACHE).reason == OffReason.AB_TOGGLE,
            ModuleStates.status(Module.STATIC_CACHE).describe()
        )
        capture(minecraft)
        yield(until("capture vanilla picture", 10) { captured != null })
        val vanillaImage = takeCapture("capture-vanilla")
        compare("Mobium and vanilla pictures match", mobiumImage, vanillaImage)
        hud.toggle()
        yield(ticks(3))
        screenshot(minecraft, "ab-vanilla")
        yield(until("screenshot ab-vanilla", 10) { shotDone })
        hud.toggle()

        KeyMapping.click(MobiumKeys.AB_TOGGLE.defaultKey)
        yield(ticks(TICKS_PER_SECOND))
        check("A/B key switches back", !AbToggle.mobiumOff && StaticCache.active, ModuleStates.status(Module.STATIC_CACHE).describe())
        yield(ticks(2 * TICKS_PER_SECOND))
        capture(minecraft)
        yield(until("capture Mobium picture again", 10) { captured != null })
        compare("Mobium picture after A/B matches vanilla", takeCapture("capture-mobium-again"), vanillaImage)

        // Cached objects are drawn from camera-relative vertices: a moved camera rewrites every visible bucket, and at
        // render distance 2 the chest wall stands in the fog band at the end of the render distance, where fog taken
        // from section-local positions (as in M3) would be visibly off.
        val scenePlayer = checkNotNull(minecraft.player)
        val x = scenePlayer.x
        val y = scenePlayer.y
        val z = scenePlayer.z
        compareView(minecraft, "moved camera") { teleport(minecraft, x - 0.13, y + 1.25, z - 4.21, -12.5f, 14f) }
        compareView(minecraft, "render distance fog") {
            teleport(minecraft, x, y, z, 0f, 10f)
            minecraft.options.renderDistance().set(2)
        }
        minecraft.options.renderDistance().set(renderDistanceBefore)
        yield(until("world settled again", 120) { SceneBuilder.isQuiet(minecraft) })
        yield(ticks(TICKS_PER_SECOND))

        // The dev command Anton can use in any still scene: the same frame pair, reported in chat. Sky and fog keep
        // easing for a few seconds after the render distance changed, so wait for that first.
        yield(ticks(5 * TICKS_PER_SECOND))
        serverCommand(minecraft, "tick freeze")
        yield(ticks(TICKS_PER_SECOND / 2))
        val before = Validation.lastResult
        checkNotNull(minecraft.player).connection.sendUnattendedCommand("mobium debug validate", null)
        yield(until("/mobium debug validate reports", 10) { Validation.lastResult !== before })
        val validated = Validation.lastResult
        check("/mobium debug validate: identical", validated?.identical == true, "$validated")
        serverCommand(minecraft, "tick unfreeze")
        yield(ticks(TICKS_PER_SECOND))

        minecraft.debugEntries.setStatus(ModuleLines.ID, DebugScreenEntryStatus.ALWAYS_ON)
        hud.toggle()
        yield(ticks(TICKS_PER_SECOND))
        val lines = debugLines()
        check("F3 mobium:modules shows the state", lines.any { "static_cache active" in it }, lines.joinToString(" / "))
        screenshot(minecraft, "f3-modules")
        yield(until("screenshot f3-modules", 10) { shotDone })

        // Forced failure through the dev command, twice: one toast only.
        val player = minecraft.player
        player?.connection?.sendUnattendedCommand("mobium debug fail static_cache", null)
        yield(ticks(5))
        player?.connection?.sendUnattendedCommand("mobium debug fail static_cache", null)
        yield(ticks(5))
        val status = ModuleStates.status(Module.STATIC_CACHE)
        check("debug fail switches the static cache off", status.reason == OffReason.FAILED && !StaticCache.active, status.describe())
        check("exactly one failure toast", failureToasts(minecraft.gui.toastManager()) == 1, "${failureToasts(minecraft.gui.toastManager())} toasts")
        yield(ticks(TICKS_PER_SECOND))
        val failedLines = debugLines()
        check("F3 shows the failure", failedLines.any { "static_cache vanilla (failed" in it }, failedLines.joinToString(" / "))
        screenshot(minecraft, "fail-toast")
        yield(until("screenshot fail-toast", 10) { shotDone })
        hud.toggle()
        yield(ticks(TICKS_PER_SECOND))
        capture(minecraft)
        yield(until("capture picture after the failure", 10) { captured != null })
        compare("chests still drawn after the failure (vanilla)", takeCapture("capture-after-failure"), vanillaImage)
        hud.toggle()

        minecraft.gui.setScreen(MobiumSettingsScreen(TitleScreen()))
        yield(ticks(5))
        val texts = widgetTexts(minecraft.gui.screen())
        val failedText = Component.translatable(OffReason.FAILED.translationKey).string
        check("settings status shows the failure", texts.any { failedText in it }, texts.take(3).joinToString(" | "))
        screenshot(minecraft, "settings-after-failure")
        yield(until("screenshot settings-after-failure", 10) { shotDone })
        minecraft.gui.setScreen(null)
        minecraft.debugEntries.setStatus(ModuleLines.ID, DebugScreenEntryStatus.NEVER)
        mobiumImage.close()
        vanillaImage.close()
    }

    /** Builds a scene of the benchmark datapack and waits until the world has settled; false if that failed. */
    private suspend fun SequenceScope<Wait>.buildScene(minecraft: Minecraft, scene: String): Boolean {
        val scenes = SceneBuilder()
        check("scene $scene build started", scenes.build(minecraft, scene))
        var nextPoll = 0
        yield(until("scene $scene ready", 120) {
            if (++nextPoll % 10 == 0) scenes.poll(minecraft)
            scenes.ready.get() || scenes.missingPack.get()
        })
        if (scenes.missingPack.get() || !scenes.ready.get()) {
            check("benchmark datapack has $scene", false, "copy benchmark/mobium-bench into the world (scripts/self_check.py does)")
            return false
        }
        yield(ticks(10 * TICKS_PER_SECOND))
        yield(until("world settled ($scene)", 120) { SceneBuilder.isQuiet(minecraft) })
        return true
    }

    /**
     * The gallery: every type the cache handles and the variants its rules tell apart. Checks what is cached, then
     * compares vanilla and Mobium on the whole gallery and after each change the cache has to notice. For the changes
     * the cache stays warm while they happen (the A/B key alone would capture everything from scratch).
     */
    private suspend fun SequenceScope<Wait>.gallery(minecraft: Minecraft) {
        yield(ticks(TICKS_PER_SECOND))
        // 10 lid blocks; 7 skulls (the powered dragon head moves and stays vanilla); 2 pots; 12 signs of which the
        // obfuscated and the sprite one stay vanilla; 3 shelves, of which the compass one may pause while its needle
        // settles.
        val counts = "${StaticCache.cachedCount(Lids.key)} ${StaticCache.cachedCount(Skulls.key)} " +
            "${StaticCache.cachedCount(Pots.key)} ${StaticCache.cachedCount(Signs.key)}"
        check("gallery: chests, skulls, pots, signs cached", counts == "10 7 2 10", "$counts; ${StaticCache.debugLine()}")
        check("gallery: shelves cached", StaticCache.cachedCount(Shelves.key) >= 2, StaticCache.debugLine())
        // The compass in its frame settles for a moment and may pause on vanilla meanwhile.
        yield(upTo("gallery: entities cached", 30) { entityCounts() == GALLERY_ENTITIES })
        check(
            "gallery: item frames, armor stands, displays cached",
            entityCounts() == GALLERY_ENTITIES,
            "${entityCounts()}; ${StaticCache.debugLine()}; " + StaticCache.describeEntries(ItemFrames.key, 7) + "; " +
                StaticCache.describeEntries(ArmorStands.key, 5) + "; " + StaticCache.describeEntries(Displays.key, 6)
        )
        compareView(minecraft, "gallery") { }

        val singleChest = BlockPos(X0 - 8, GROUND, Z0 + 9)
        val enderChest = BlockPos(X0 - 1, GROUND, Z0 + 9)
        effect(minecraft, "gallery: chest opened", 2) { level -> level.blockEvent(singleChest, Blocks.CHEST, 1, 1) }
        effect(minecraft, "gallery: chest closed", 3) { level -> level.blockEvent(singleChest, Blocks.CHEST, 1, 0) }
        effect(minecraft, "gallery: breaking overlay", 1) { level -> level.destroyBlockProgress(BREAKER_ID, enderChest, 5) }
        effect(minecraft, "gallery: breaking overlay gone", 1) { level -> level.destroyBlockProgress(BREAKER_ID, enderChest, -1) }
        // A roof over the single and the double chest cuts their sky light. Block light would not do: vanilla's
        // lightmap flickers block light randomly every client tick (LightmapRenderStateExtractor), so two pictures
        // of block-lit surfaces never match.
        effect(minecraft, "gallery: light changed", 2) {
            serverCommand(minecraft, "fill ${X0 - 9} ${GROUND + 1} ${Z0 + 8} ${X0 - 4} ${GROUND + 1} ${Z0 + 10} minecraft:smooth_stone_slab")
        }
        effect(minecraft, "gallery: sign text changed", 1) {
            serverCommand(minecraft, "data merge block ${X0 - 8} ${GROUND + 1} ${Z0 + 12} {front_text:{messages:[\"Changed\",\"text\",\"\",\"\"]}}")
        }
        effect(minecraft, "gallery: chest turned", 1) {
            serverCommand(minecraft, "setblock ${X0 - 3} $GROUND ${Z0 + 9} minecraft:trapped_chest[facing=east]")
        }
        // Entities whose picture changes while the cache is warm: captured again from the next frame.
        effect(minecraft, "gallery: item frame turned", 1) {
            serverCommand(minecraft, "data merge entity @e[tag=mobium_gallery_frame_plain,limit=1] {ItemRotation:2b}")
        }
        effect(minecraft, "gallery: item frame item swapped", 1) {
            serverCommand(minecraft, "data merge entity @e[tag=mobium_gallery_frame_glow,limit=1] {Item:{id:\"minecraft:carrot\",count:1}}")
        }
        effect(minecraft, "gallery: display moved", 1) {
            serverCommand(minecraft, "execute as @e[tag=mobium_gallery_display_block,limit=1] at @s run tp @s ~0.5 ~ ~")
        }
        effect(minecraft, "gallery: armor stand pose changed", 1) {
            serverCommand(minecraft, "data merge entity @e[tag=mobium_gallery_stand_holding,limit=1] {Pose:{Head:[-20f,-30f,0f]}}")
        }
        yield(upTo("gallery: entities cached again", 30) { entityCounts() == GALLERY_ENTITIES })
        check("gallery: changed entities cached again", entityCounts() == GALLERY_ENTITIES, "${entityCounts()}; ${StaticCache.debugLine()}")
        lightElsewhere(minecraft)
        entityLinks(minecraft)

        if (Compat.isLoaded(CompatMod.IRIS)) {
            /*? if fabric {*/
            shaderPack(minecraft)
            /*?} else {*/
            /*// Iris 1.11.7 on NeoForge crashes with Complementary Reimagined r5.9.3 in CenterDepthSampler ("Missing
            // uniform InSampler"), with Mobium switched off as well (`--mode iris-without-mobium`).
            results += "NOTE the shader pack checks are skipped on NeoForge (Iris 1.11.7 crashes there with Complementary, Mobium off or on)"
            *//*?}*/
        }
        if (Compat.isLoaded(CompatMod.EMF)) freshAnimations(minecraft)
    }

    /**
     * Light updates in other sections leave the cached objects on the fast path: a glowstone block 56 blocks west of
     * the gallery (its light reaches no gallery section) switched on and off ten times. Before beta2 every light update
     * anywhere sent every cached object through the full check once; now full checks per frame stay as before (the
     * shelves take it every frame). Then the 600-frame sweep forgets the sections all entries have seen.
     */
    private suspend fun SequenceScope<Wait>.lightElsewhere(minecraft: Minecraft) {
        yield(until("world settled (light elsewhere)", 60) { SceneBuilder.isQuiet(minecraft) })
        val baselineFrame = Sections.frame
        val baselineTotals = StaticCache.totals.copyOf()
        yield(ticks(2 * TICKS_PER_SECOND))
        val baselineFrames = Sections.frame - baselineFrame
        val baselineSlow = StaticCache.totals[1] - baselineTotals[1]
        results += "NOTE frame rate in the gallery: about ${baselineFrames / 2} FPS"

        val startFrame = Sections.frame
        val startTotals = StaticCache.totals.copyOf()
        val far = "${X0 - 56} ${GROUND + 2} ${Z0 + 9}"
        // The sections kept are read every tick: a sweep may already prune them before the toggling ends.
        var kept = 0
        for (i in 0 until 10) {
            serverCommand(minecraft, "setblock $far minecraft:${if (i % 2 == 0) "glowstone" else "air"}")
            yield(Wait("light toggle $i", 4, required = false) { kept = maxOf(kept, StaticCache.lightSectionsKept); false })
        }
        yield(Wait("light toggles settle", TICKS_PER_SECOND, required = false) {
            kept = maxOf(kept, StaticCache.lightSectionsKept)
            false
        })
        val frames = Sections.frame - startFrame
        val slow = StaticCache.totals[1] - startTotals[1]
        val lightUpdates = StaticCache.totals[2] - startTotals[2]
        val extraPerFrame = slow.toDouble() / frames.coerceAtLeast(1) - baselineSlow.toDouble() / baselineFrames.coerceAtLeast(1)
        check(
            "gallery: light updates elsewhere keep the fast path",
            lightUpdates > 0 && extraPerFrame < 0.5,
            String.format(
                Locale.ROOT, "%d light updates, %d full checks in %d frames (before: %d in %d), %+.2f per frame",
                lightUpdates, slow, frames, baselineSlow, baselineFrames, extraPerFrame
            )
        )
        yield(upTo("light sections pruned", 60) { StaticCache.lightSectionsKept < kept })
        check(
            "gallery: the sweep forgets light updates every entry has seen",
            kept > 0 && StaticCache.lightSectionsKept < kept,
            "at most $kept sections kept while toggling, ${StaticCache.lightSectionsKept} after the sweep"
        )
    }

    /** Render states keep a reference to their entity only for the types the cache takes (not the player's). */
    private fun entityLinks(minecraft: Minecraft) {
        val level = minecraft.level ?: return
        val dispatcher = minecraft.entityRenderDispatcher
        val player = checkNotNull(minecraft.player)
        val playerLink = (dispatcher.extractEntity(player, 1f) as EntityStateSource).`mobium$entity`()
        val frame = level.entitiesForRendering().firstOrNull { EntityPolicy.ruleFor(it) === ItemFrames }
        val frameLink = frame?.let { (dispatcher.extractEntity(it, 1f) as EntityStateSource).`mobium$entity`() }
        check(
            "gallery: only cached types' render states keep their entity",
            playerLink == null && frame != null && frameLink === frame,
            "player linked ${playerLink != null}, item frame ${if (frame == null) "missing" else "linked ${frameLink === frame}"}"
        )
    }

    /** Cached item frames, armor stands and displays, as "frames stands displays". */
    private fun entityCounts(): String = "${StaticCache.cachedCount(ItemFrames.key)} " +
        "${StaticCache.cachedCount(ArmorStands.key)} ${StaticCache.cachedCount(Displays.key)}"

    /**
     * With a Fresh Animations zip in `resourcepacks/`: EMF replaces head models with the pack's, the cache captures
     * them through EMF (models EMF animates stay vanilla), and the picture matches vanilla's.
     */
    private suspend fun SequenceScope<Wait>.freshAnimations(minecraft: Minecraft) {
        val repository = minecraft.resourcePackRepository
        repository.reload()
        val pack = repository.availableIds.firstOrNull { "fresh" in it.lowercase(Locale.ROOT) }
        if (pack == null) {
            results += "NOTE no Fresh Animations zip in resourcepacks/, the Fresh Animations checks were skipped"
            return
        }
        repository.addPack(pack)
        minecraft.options.updateResourcePacks(repository)
        yield(ticks(10))
        yield(until("resource pack $pack loaded", 120) { minecraft.gui.overlay() == null })
        yield(ticks(3 * TICKS_PER_SECOND))
        yield(until("world settled (Fresh Animations)", 60) { SceneBuilder.isQuiet(minecraft) })
        val counts = "${StaticCache.cachedCount(Lids.key)} ${StaticCache.cachedCount(Skulls.key)} " +
            "${StaticCache.cachedCount(Pots.key)} ${StaticCache.cachedCount(Signs.key)}"
        check("Fresh Animations: cache active", StaticCache.active && StaticCache.lastDrawn > 0, "$pack; $counts; ${StaticCache.debugLine()}")
        comparePair(minecraft, "Fresh Animations: gallery", "fresh-gallery")
        repository.removePack(pack)
        minecraft.options.updateResourcePacks(repository)
        yield(ticks(10))
        yield(until("resource pack $pack removed", 120) { minecraft.gui.overlay() == null })
        yield(ticks(2 * TICKS_PER_SECOND))
    }

    /**
     * With the first shader pack in `shaderpacks/`: the cache captures in Iris's extended vertex formats and its
     * picture matches vanilla's. The pack's temporal anti-aliasing and cloud movement would make two frames differ on
     * their own, so they are switched off in the pack's options for the check (restored afterwards).
     */
    private suspend fun SequenceScope<Wait>.shaderPack(minecraft: Minecraft) {
        val pack = IrisCheck.firstPack()
        if (pack == null) {
            results += "NOTE no shader pack in shaderpacks/, the shader pack checks were skipped"
            return
        }
        val restore = IrisCheck.select(pack, mapOf("TAA_DEFINE" to "0", "CLOUD_SPEED_MULT" to "0"))
        yield(until("shader pack $pack in use", 120) { IrisCompat.isShaderPackInUse() })
        yield(ticks(5 * TICKS_PER_SECOND))
        yield(until("world settled (shader pack)", 120) { SceneBuilder.isQuiet(minecraft) })
        yield(ticks(TICKS_PER_SECOND))
        val counts = "${StaticCache.cachedCount(Lids.key)} ${StaticCache.cachedCount(Skulls.key)} " +
            "${StaticCache.cachedCount(Pots.key)} ${StaticCache.cachedCount(Signs.key)}"
        check("shader pack: cache active", StaticCache.active && counts == "10 7 2 10", "$pack; $counts; ${StaticCache.debugLine()}")
        yield(upTo("shader pack: entities cached", 30) { entityCounts() == GALLERY_ENTITIES })
        check("shader pack: entities cached", entityCounts() == GALLERY_ENTITIES, "${entityCounts()}; ${StaticCache.debugLine()}")
        val sizes = StaticCache.vertexSizes()
        check("shader pack: captures use Iris's vertex formats", sizes.isNotEmpty() && 36 !in sizes && 28 !in sizes, "vertex sizes $sizes")
        // Iris's shadow pass has timers of its own; its stages run inside it, so they add up to at most its total.
        Sections.forceTimers = true
        yield(ticks(3 * TICKS_PER_SECOND))
        val shadow = Sections.averageMillis(Sections.SHADOW)
        val parts = Sections.averageMillis(Sections.SHADOW_EXTRACT) + Sections.averageMillis(Sections.SHADOW_SUBMIT) +
            Sections.averageMillis(Sections.SHADOW_PREPARE) + Sections.averageMillis(Sections.SHADOW_DRAW)
        check(
            "shader pack: shadow pass timed",
            shadow > 0.0 && Sections.averageMillis(Sections.SHADOW_PREPARE) > 0.0 &&
                Sections.averageMillis(Sections.SHADOW_DRAW) > 0.0 && parts <= shadow,
            shadowTimings()
        )
        results += "NOTE shadow pass in the gallery: ${shadowTimings()}"
        Sections.forceTimers = false
        comparePair(minecraft, "shader pack: gallery", "shaderpack-gallery")
        // Shader packs add noise of their own from frame to frame (dithering); two vanilla frames show how much.
        KeyMapping.click(MobiumKeys.AB_TOGGLE.defaultKey)
        yield(ticks(TICKS_PER_SECOND))
        val floor = Validation.capturePair(toggle = false)
        yield(until("noise pictures (shader pack)", 10) { floor.done })
        val first = floor.first
        val second = floor.second
        if (first != null && second != null) {
            results += "NOTE shader pack noise between two vanilla frames: ${Validation.compare(first, second)}"
            first.close()
            second.close()
        }
        KeyMapping.click(MobiumKeys.AB_TOGGLE.defaultKey)
        yield(ticks(TICKS_PER_SECOND))
        restore()
        yield(until("shader pack off", 120) { !IrisCompat.isShaderPackInUse() })
        yield(ticks(2 * TICKS_PER_SECOND))
        check("cache back on vanilla formats", StaticCache.active && StaticCache.vertexSizes().all { it == 36 || it == 28 }, "vertex sizes ${StaticCache.vertexSizes()}")
        Sections.forceTimers = true
        yield(ticks(2 * TICKS_PER_SECOND))
        check("no shadow pass timed without a shader pack", Sections.averageMillis(Sections.SHADOW) == 0.0, shadowTimings())
        Sections.forceTimers = false
    }

    private fun shadowTimings(): String = Sections.NAMES.indices.filter { Sections.NAMES[it].startsWith("shadow") }
        .joinToString { String.format(Locale.ROOT, "%s %.3f ms", Sections.NAMES[it], Sections.averageMillis(it)) }

    /**
     * Applies a change on the server while the cache is warm, waits [seconds], then compares Mobium's picture with
     * vanilla's. Ticks are frozen for the two pictures, so that anything vanilla animates (the powered dragon head)
     * stands still between them.
     */
    private suspend fun SequenceScope<Wait>.effect(
        minecraft: Minecraft,
        name: String,
        seconds: Int,
        change: (ServerLevel) -> Unit
    ) {
        val server = minecraft.singleplayerServer ?: return
        server.execute { change(server.overworld()) }
        yield(ticks(seconds * TICKS_PER_SECOND))
        yield(until("world settled ($name)", 60) { SceneBuilder.isQuiet(minecraft) })
        comparePair(minecraft, name, "gallery-" + name.removePrefix("gallery: ").replace(' ', '-'))
    }

    /**
     * Compares Mobium's picture with vanilla's from two consecutive frames: at the start of one frame the last frame
     * (Mobium, cache warm) is captured and Mobium is switched to vanilla, at the start of the next that vanilla frame is
     * captured and Mobium is switched back. Both frames belong to one client tick (else it is tried again), because
     * vanilla's lightmap flickers block light randomly every tick (`LightmapRenderStateExtractor`). Ticks are frozen
     * as well, so that what vanilla animates (the powered dragon head) stands still.
     */
    private suspend fun SequenceScope<Wait>.comparePair(minecraft: Minecraft, name: String, file: String) {
        serverCommand(minecraft, "tick freeze")
        yield(ticks(TICKS_PER_SECOND / 2))
        check("cache draws ($name)", StaticCache.active && StaticCache.lastDrawn > 0, StaticCache.debugLine())
        var attempt = 1
        while (true) {
            val pair = Validation.capturePair()
            yield(until("pictures ($name)", 10) { pair.done })
            val mobium = pair.first
            val vanilla = pair.second
            if (mobium == null || vanilla == null) {
                check("$name: pictures taken", false)
                break
            }
            if (!pair.sameTick && attempt < Validation.PAIR_ATTEMPTS) {
                mobium.close()
                vanilla.close()
                attempt++
                yield(ticks(TICKS_PER_SECOND))
                continue
            }
            mobium.writeToFile(directory.resolve("screenshots").resolve("$file-mobium.png"))
            vanilla.writeToFile(directory.resolve("screenshots").resolve("$file-vanilla.png"))
            val note = if (pair.sameTick) "" else " (a client tick between the frames)"
            compare("$name: Mobium and vanilla pictures match$note", mobium, vanilla)
            mobium.close()
            vanilla.close()
            break
        }
        serverCommand(minecraft, "tick unfreeze")
        yield(ticks(TICKS_PER_SECOND))
    }


    private fun serverCommand(minecraft: Minecraft, command: String) {
        val server = minecraft.singleplayerServer ?: return
        server.execute { server.commands.performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command) }
    }

    /** Sets up a view, lets it settle and compares Mobium's picture with vanilla's ([comparePair]). */
    private suspend fun SequenceScope<Wait>.compareView(minecraft: Minecraft, name: String, setUp: () -> Unit) {
        setUp()
        yield(ticks(2 * TICKS_PER_SECOND))
        yield(until("world settled ($name)", 120) { SceneBuilder.isQuiet(minecraft) })
        yield(ticks(TICKS_PER_SECOND))
        comparePair(minecraft, name, "capture-" + name.replace(' ', '-'))
    }

    /** Moves the player through the integrated server, as `/tp` does (the benchmark world needs no cheats). */
    private fun teleport(minecraft: Minecraft, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        val server = minecraft.singleplayerServer ?: return
        val uuid = checkNotNull(minecraft.player).uuid
        val command = String.format(Locale.ROOT, "execute as %s run tp @s %.4f %.4f %.4f %.2f %.2f", uuid, x, y, z, yaw, pitch)
        server.execute { server.commands.performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command) }
    }

    private fun debugLines(): List<String> {
        val lines = ArrayList<String>()
        val displayer = object : DebugScreenDisplayer {
            override fun addPriorityLine(line: String) {
                lines += line
            }

            override fun addLine(line: String) {
                lines += line
            }

            override fun addToGroup(group: Identifier, lines: Collection<String>) {
                lines.forEach(::addLine)
            }

            override fun addToGroup(group: Identifier, lines: String) = addLine(lines)
        }
        ModuleLines.display(displayer, null, null, null)
        return lines
    }

    /** Failure toasts shown or queued, read from the toast manager's private lists (dev check only). */
    private fun failureToasts(manager: ToastManager): Int {
        var count = 0
        for (name in listOf("visibleToasts", "queued")) {
            val field = ToastManager::class.java.getDeclaredField(name).apply { isAccessible = true }
            for (item in field.get(manager) as Collection<*>) {
                val toast = if (item is SystemToast) item else item?.javaClass?.getDeclaredMethod("getToast")?.apply { isAccessible = true }?.invoke(item)
                if (toast is SystemToast && toast.token == Fallback.TOAST_ID) count++
            }
        }
        return count
    }

    // endregion

    // region pictures

    private var shotDone = false
    private var captured: NativeImage? = null

    private fun screenshot(minecraft: Minecraft, name: String) {
        shotDone = false
        Screenshot.grab(directory.toFile(), "$name.png", minecraft.gameRenderer.mainRenderTarget(), 1) { shotDone = true }
    }

    private fun capture(minecraft: Minecraft) {
        captured = null
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget()) { captured = it }
    }

    /** The last capture, also saved as `screenshots/<name>.png` for a look by eye. */
    private fun takeCapture(name: String): NativeImage {
        val image = checkNotNull(captured)
        captured = null
        image.writeToFile(directory.resolve("screenshots").resolve("$name.png"))
        return image
    }

    /** Pixel comparison by [Validation.FrameDiff.identical]: only isolated edge pixels may change at all. */
    private fun compare(name: String, a: NativeImage, b: NativeImage) {
        val diff = Validation.compare(a, b)
        check(name, diff.identical, "$diff")
    }

    // endregion

    private fun widgets(root: GuiEventListener?): List<GuiEventListener> {
        val result = ArrayList<GuiEventListener>()
        fun walk(node: GuiEventListener) {
            result += node
            if (node is ContainerEventHandler) node.children().forEach(::walk)
        }
        if (root is ContainerEventHandler) root.children().forEach(::walk)
        return result
    }

    private fun widgetTexts(screen: Screen?): List<String> =
        widgets(screen).filterIsInstance<AbstractWidget>().map { it.message.string }.filter(String::isNotBlank)

    private fun finish(minecraft: Minecraft) {
        restoreOptions?.invoke()
        restoreOptions = null
        val checks = results.count { !it.startsWith("NOTE") }
        val summary = if (failures == 0) "ALL $checks CHECKS PASSED" else "$failures OF $checks CHECKS FAILED"
        results += summary
        Files.write(directory.resolve("report.txt"), listOf("Mobium self-check, ${Compat.LOADER}") + results)
        Mobium.LOGGER.info("Mobium self-check finished: {}", summary)
        minecraft.stop()
    }
}
