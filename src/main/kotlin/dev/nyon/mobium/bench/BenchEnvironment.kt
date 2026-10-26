package dev.nyon.mobium.bench

import dev.nyon.mobium.cache.StaticCache
import dev.nyon.mobium.parts.PartTransform
import dev.nyon.mobium.core.Compat
import dev.nyon.mobium.core.CompatMod
import dev.nyon.mobium.core.IrisCompat
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * What a benchmark run depends on besides the scene: the setup (mods, shader pack, resource packs, Mobium features),
 * the display and the machine's power state. Everything here is read once per run on the render thread; the results
 * go into `summary.csv`, and anything that breaks the protocol in docs/benchmarks.md is reported in chat before the
 * run starts, because the first round of baselines lost a third of its data to exactly these conditions.
 */
object BenchEnvironment {
    /** Window size the protocol asks for. */
    const val PROTOCOL_WIDTH = 1920
    const val PROTOCOL_HEIGHT = 1080

    /**
     * The setup an instance is meant to run, set by `benchmark/prism_instances.py` as `-Dmobium.bench.expect=…` in the
     * instance's Java arguments; null when the game was started some other way.
     */
    val expectedSetup: String? = System.getProperty("mobium.bench.expect")?.takeIf(String::isNotBlank)

    /** One run's surroundings, as written to `summary.csv`. */
    class Snapshot(
        @JvmField val display: String,
        @JvmField val resolution: String,
        @JvmField val power: String,
        @JvmField val powerProfile: String
    )

    fun snapshot(): Snapshot {
        val window = Minecraft.getInstance().window
        val monitor = window.findBestMonitor()
        val display = if (monitor == null) "" else "${monitor.name()} ${window.activeVideoMode ?: monitor.currentMode()}"
        val machine = MachineState.SYSTEM
        return Snapshot(display, "${window.width}x${window.height}", machine.power(), machine.powerProfile())
    }

    /**
     * The setup name for file names and `summary.csv`: base renderer, Iris with an active pack, EMF, Fresh Animations
     * if selected, and the Mobium features that are on (`nodraw` while the static cache skips its draw calls, Gate B's
     * measurement of what the cached objects cost without drawing them; `parts` while Module 2 runs).
     */
    fun detectSetup(): String {
        val parts = ArrayList<String>()
        parts += setupWithoutMobium()
        // Before the cache's suffixes, which Gate A and B look for at the end.
        if (PartTransform.active) parts += "parts"
        if (StaticCache.active) parts += "cache"
        if (StaticCache.active && StaticCache.skipDraws) parts += "nodraw"
        return parts.joinToString("+")
    }

    /** [detectSetup] without Mobium's own features, which is what an instance's [expectedSetup] describes. */
    fun setupWithoutMobium(): String {
        val parts = ArrayList<String>()
        parts += if (Compat.isLoaded(CompatMod.SODIUM)) "sodium" else "vanilla"
        if (Compat.isLoaded(CompatMod.IRIS) && IrisCompat.isShaderPackInUse()) parts += "iris"
        if (Compat.isLoaded(CompatMod.EMF)) parts += "emf"
        val packs = Minecraft.getInstance().resourcePackRepository.selectedIds
        if (packs.any { "fresh" in it.lowercase(Locale.ROOT) }) parts += "fa"
        return parts.joinToString("+")
    }

    /** Null when the instance runs the setup it was made for (or declares none), else why not. */
    fun setupMismatch(): Component? {
        val expected = expectedSetup ?: return null
        val actual = setupWithoutMobium()
        if (actual == expected) return null
        return Component.translatable("mobium.bench.wrong_setup", expected, actual)
    }

    /** Conditions that make a run incomparable with the protocol; empty when everything matches. */
    fun warnings(snapshot: Snapshot): List<Component> {
        val warnings = ArrayList<Component>()
        setupMismatch()?.let(warnings::add)
        if (snapshot.power == "battery") warnings += Component.translatable("mobium.bench.on_battery")
        if (snapshot.powerProfile in MachineState.LOW_POWER_PROFILES) {
            warnings += Component.translatable("mobium.bench.low_power", snapshot.powerProfile)
        }
        val window = Minecraft.getInstance().window
        if (window.width != PROTOCOL_WIDTH || window.height != PROTOCOL_HEIGHT) {
            warnings += Component.translatable(
                "mobium.bench.resolution", snapshot.resolution, "${PROTOCOL_WIDTH}x$PROTOCOL_HEIGHT", snapshot.display
            )
        }
        return warnings
    }
}
