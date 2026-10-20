package dev.nyon.mobium.debug.selfcheck

import com.mojang.blaze3d.platform.NativeImage
import dev.nyon.konfig.config.loadConfig
import dev.nyon.mobium.Mobium
import dev.nyon.mobium.MobiumKeys
import dev.nyon.mobium.bench.SceneBuilder
import dev.nyon.mobium.cache.Lids
import dev.nyon.mobium.cache.Signs
import dev.nyon.mobium.cache.StaticCache
import dev.nyon.mobium.config.CacheConfig
import dev.nyon.mobium.config.Config
import dev.nyon.mobium.config.applyConfig
import dev.nyon.mobium.config.config
import dev.nyon.mobium.config.screen.MobiumSettingsScreen
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.CompatMod
import dev.nyon.mobium.core.Fallback
import dev.nyon.mobium.core.Module
import dev.nyon.mobium.core.ModuleStates
import dev.nyon.mobium.core.OffReason
import dev.nyon.mobium.debug.AbToggle
import dev.nyon.mobium.debug.ModuleLines
import dev.nyon.mobium.debug.Validation
import net.minecraft.client.CloudStatus
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
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
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import java.nio.file.Files
import java.nio.file.Path
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
    private const val CHESTS = 1000
    private const val SIGNS = 300

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
        Files.delete(request)
        this.directory = directory
        Files.createDirectories(directory.resolve("screenshots"))
        Mobium.LOGGER.info("Mobium self-check started ({} on {})", Compat.LOADER, minecraft.gameDirectory)
        steps = scenario(minecraft).iterator()
    }

    /** Advances the scenario; called at the end of every client tick. */
    fun tick() {
        val iterator = steps ?: return
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
        val saved = config.deepCopy()
        applyConfig(Config(cache = CacheConfig(prototype = true)))
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

    private suspend fun SequenceScope<Wait>.inWorld(minecraft: Minecraft) {
        minecraft.createWorldOpenFlows().openWorld(WORLD) { }
        yield(until("world loaded", 180) { minecraft.level != null && minecraft.player != null && minecraft.gui.screen() == null })
        yield(ticks(40))
        val scenes = SceneBuilder()
        check("scene build started", scenes.build(minecraft, SCENE))
        var nextPoll = 0
        yield(until("scene $SCENE ready", 120) {
            if (++nextPoll % 10 == 0) scenes.poll(minecraft)
            scenes.ready.get() || scenes.missingPack.get()
        })
        check("benchmark datapack present", !scenes.missingPack.get())
        yield(ticks(10 * TICKS_PER_SECOND))
        yield(until("world settled", 120) { SceneBuilder.isQuiet(minecraft) })
        minecraft.gui.hud.chat.clearMessages(false)
        // Drifting clouds are the only thing that moves in the scene; without them two pictures of the same frame
        // state match. Restored with the other options at the end.
        minecraft.options.cloudStatus().set(CloudStatus.OFF)
        val hud = minecraft.gui.hud
        if (!hud.isHidden) hud.toggle()
        yield(ticks(TICKS_PER_SECOND))

        check("static cache active", StaticCache.active && ModuleStates.isActive(Module.STATIC_CACHE), ModuleStates.status(Module.STATIC_CACHE).describe())
        check(
            "chests and signs cached",
            StaticCache.cachedCount(Lids.key) == CHESTS && StaticCache.cachedCount(Signs.key) == SIGNS,
            StaticCache.debugLine()
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

    /**
     * Sets up a view, lets it settle and compares vanilla's picture with Mobium's (both through the A/B key, which
     * drops the cache and captures everything again).
     */
    private suspend fun SequenceScope<Wait>.compareView(minecraft: Minecraft, name: String, setUp: () -> Unit) {
        setUp()
        yield(ticks(2 * TICKS_PER_SECOND))
        yield(until("world settled ($name)", 120) { SceneBuilder.isQuiet(minecraft) })
        yield(ticks(TICKS_PER_SECOND))
        val file = name.replace(' ', '-')
        KeyMapping.click(MobiumKeys.AB_TOGGLE.defaultKey)
        yield(ticks(TICKS_PER_SECOND))
        capture(minecraft)
        yield(until("capture vanilla picture ($name)", 10) { captured != null })
        val vanilla = takeCapture("capture-$file-vanilla")
        KeyMapping.click(MobiumKeys.AB_TOGGLE.defaultKey)
        yield(ticks(TICKS_PER_SECOND))
        check("cache draws ($name)", StaticCache.active && StaticCache.lastDrawn > 0, StaticCache.debugLine())
        capture(minecraft)
        yield(until("capture Mobium picture ($name)", 10) { captured != null })
        val mobium = takeCapture("capture-$file-mobium")
        compare("$name: Mobium and vanilla pictures match", mobium, vanilla)
        mobium.close()
        vanilla.close()
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
