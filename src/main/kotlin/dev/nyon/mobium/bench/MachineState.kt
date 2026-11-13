package dev.nyon.mobium.bench

import java.nio.file.Files
import java.nio.file.Path

/**
 * Power, GPU clock and GPU throttle state of the machine, read from Linux sysfs (empty strings and no files elsewhere).
 * Benchmarks record it because it changes results without changing anything visible: on the development laptop vanilla
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

    /**
     * The file saying why the render GPU's clock is not higher, read by [readThrottle]: `xe` lists the active reasons
     * per GT (`device/tile0/gt0/freq0/throttle/reasons`), `i915` has a 0/1 file per reason next to
     * `gt/gt0/throttle_reason_status`, which this returns. Null when there is none. docs/benchmarks.md, *Frames between
     * sessions follow the GPU clock*, says what the reasons showed.
     */
    fun gpuThrottleFile(): Path? {
        for (card in list(sys.resolve("class/drm"))) {
            val name = card.fileName.toString()
            if (!name.startsWith("card") || '-' in name) continue
            val xe = card.resolve("device/tile0/gt0/freq0/throttle/reasons")
            if (Files.isReadable(xe)) return xe
            val i915 = card.resolve("gt/gt0/throttle_reason_status")
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

        /** The reasons `i915` reports, each in a file `throttle_reason_<reason>` holding 0 or 1. */
        private val I915_THROTTLE_REASONS =
            arrayOf("pl1", "pl2", "pl4", "thermal", "prochot", "ratl", "vr_tdc", "vr_thermalert")

        /**
         * Reads a file found by [gpuThrottleFile]: the reasons that hold the GPU's clock down right now as the
         * driver names them (`pl1`, `pl1 prochot`; [ThrottleTally] splits them at spaces and commas), `none` when
         * nothing does, `other` for a limit `i915` does not name, or empty if it cannot be read. `xe` writes them into
         * one file; `i915`'s status file is 0 or 1, and when it is 1 the files next to it say which.
         */
        fun readThrottle(file: Path): String {
            if (file.fileName.toString() != "throttle_reason_status") return read(file)
            when (read(file)) {
                "0" -> return "none"
                "1" -> {}
                else -> return ""
            }
            val reasons = StringBuilder()
            for (reason in I915_THROTTLE_REASONS) {
                if (read(file.resolveSibling("throttle_reason_$reason")) != "1") continue
                if (reasons.isNotEmpty()) reasons.append(' ')
                reasons.append(reason)
            }
            // The status covers more limits than the eight named files.
            return if (reasons.isEmpty()) "other" else reasons.toString()
        }

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
