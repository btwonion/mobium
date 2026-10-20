package dev.nyon.mobium.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MachineStateTest {
    @TempDir
    lateinit var sys: Path

    private fun write(path: String, content: String) {
        val file = sys.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    @Test
    fun `nothing known on a machine without sysfs`() {
        val state = MachineState(sys.resolve("missing"))
        assertEquals("", state.power())
        assertEquals("", state.powerProfile())
        assertNull(state.gpuClockFile())
    }

    @Test
    fun `mains supply decides between ac and battery`() {
        write("class/power_supply/BAT0/type", "Battery\n")
        write("class/power_supply/AC/type", "Mains\n")
        write("class/power_supply/AC/online", "0\n")
        assertEquals("battery", MachineState(sys).power())
        write("class/power_supply/AC/online", "1\n")
        assertEquals("ac", MachineState(sys).power())
    }

    @Test
    fun `platform profile is read as is`() {
        write("firmware/acpi/platform_profile", "low-power\n")
        assertEquals("low-power", MachineState(sys).powerProfile())
    }

    @Test
    fun `xe clock is found on the card and connectors are skipped`() {
        write("class/drm/card1-eDP-1/status", "connected\n")
        write("class/drm/card1/device/tile0/gt0/freq0/act_freq", "1950\n")
        val file = MachineState(sys).gpuClockFile()
        assertEquals(sys.resolve("class/drm/card1/device/tile0/gt0/freq0/act_freq"), file)
        assertEquals(1950, MachineState.readClock(file!!))
    }

    @Test
    fun `i915 clock is the fallback`() {
        write("class/drm/card0/gt_act_freq_mhz", "300\n")
        assertEquals(300, MachineState.readClock(MachineState(sys).gpuClockFile()!!))
    }

    @Test
    fun `unreadable clock is negative`() {
        assertEquals(-1, MachineState.readClock(sys.resolve("nothing")))
    }
}
