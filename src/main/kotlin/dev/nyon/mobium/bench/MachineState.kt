package dev.nyon.mobium.bench

import java.nio.file.Files
import java.nio.file.Path

/**
 * Power and GPU clock state of the machine, read from Linux sysfs (empty strings and no clock elsewhere). Benchmarks
 * record it because it changes results without changing anything visible: on the development laptop vanilla
 * `storage_room` ran 5× slower in some sessions (the iGPU's clock range is 400–1950 MHz) and slower still on battery
 * (docs/benchmarks.md, *Measurement pitfalls*).
 *
 * Pure file reading, so the unit tests run it against a fake sysfs tree.
 */
class MachineState(private val sys: Path) {
    /** `ac` or `battery` when a mains supply is reported, otherwise empty. */
    fun power(): String {
        val supplies = list(sys.resolve("class/power_supply"))
        val mains = supplies.filter { read(it.resolve("type")) == "Mains" }
        if (mains.isEmpty()) return ""
        return if (mains.any { read(it.resolve("online")) == "1" }) "ac" else "battery"
    }

    /** The ACPI platform profile (`low-power`, `balanced`, `performance`, …), or empty. */
    fun powerProfile(): String = read(sys.resolve("firmware/acpi/platform_profile"))

    /**
     * The file holding the render GPU's actual clock in MHz: `xe` exposes it per GT
     * (`device/tile0/gt0/freq0/act_freq`), `i915` per card (`gt_act_freq_mhz`). Null when there is none.
     */
    fun gpuClockFile(): Path? {
        for (card in list(sys.resolve("class/drm"))) {
            val name = card.fileName.toString()
            if (!name.startsWith("card") || '-' in name) continue
            val xe = card.resolve("device/tile0/gt0/freq0/act_freq")
            if (Files.isReadable(xe)) return xe
            val i915 = card.resolve("gt_act_freq_mhz")
            if (Files.isReadable(i915)) return i915
        }
        return null
    }

    companion object {
        val SYSTEM = MachineState(Path.of("/sys"))

        /** Profiles that cap CPU and GPU clocks; a benchmark in one of them is not comparable. */
        val LOW_POWER_PROFILES = setOf("low-power", "quiet", "cool")

        /** Reads a clock file written by [gpuClockFile]; -1 if it cannot be read. */
        fun readClock(file: Path): Int = read(file).toIntOrNull() ?: -1

        private fun read(file: Path): String = try {
            Files.readString(file).trim()
        } catch (_: Exception) {
            ""
        }

        private fun list(directory: Path): List<Path> = try {
            Files.list(directory).use { stream -> stream.sorted().toList() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
