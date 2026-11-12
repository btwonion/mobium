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
        assertNull(state.gpuThrottleFile())
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

    @Test
    fun `xe throttle reasons are read as the driver lists them`() {
        write("class/drm/card1-eDP-1/status", "connected\n")
        write("class/drm/card1/device/tile0/gt0/freq0/throttle/reasons", "none\n")
        write("class/drm/card1/device/tile0/gt0/freq0/throttle/reason_pl1", "0\n")
        val file = MachineState(sys).gpuThrottleFile()
        assertEquals(sys.resolve("class/drm/card1/device/tile0/gt0/freq0/throttle/reasons"), file)
        assertEquals("none", MachineState.readThrottle(file!!))
        write("class/drm/card1/device/tile0/gt0/freq0/throttle/reasons", "pl1 prochot\n")
        assertEquals("pl1 prochot", MachineState.readThrottle(file))
    }

    @Test
    fun `i915 throttle reasons come from one file per reason`() {
        write("class/drm/card0/gt/gt0/throttle_reason_status", "0\n")
        write("class/drm/card0/gt/gt0/throttle_reason_pl1", "0\n")
        write("class/drm/card0/gt/gt0/throttle_reason_thermal", "0\n")
        val file = MachineState(sys).gpuThrottleFile()!!
        assertEquals(sys.resolve("class/drm/card0/gt/gt0/throttle_reason_status"), file)
        assertEquals("none", MachineState.readThrottle(file))
        write("class/drm/card0/gt/gt0/throttle_reason_status", "1\n")
        write("class/drm/card0/gt/gt0/throttle_reason_pl1", "1\n")
        write("class/drm/card0/gt/gt0/throttle_reason_thermal", "1\n")
        assertEquals("pl1 thermal", MachineState.readThrottle(file))
        write("class/drm/card0/gt/gt0/throttle_reason_pl1", "0\n")
        write("class/drm/card0/gt/gt0/throttle_reason_thermal", "0\n")
        assertEquals("other", MachineState.readThrottle(file))
    }

    @Test
    fun `no throttle file without a driver that has one`() {
        write("class/drm/card0/gt_act_freq_mhz", "300\n")
        assertNull(MachineState(sys).gpuThrottleFile())
        assertEquals("", MachineState.readThrottle(sys.resolve("nothing/reasons")))
        assertEquals("", MachineState.readThrottle(sys.resolve("nothing/throttle_reason_status")))
    }
}
