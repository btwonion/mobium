package dev.nyon.mobium.bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OperatingSystemTest {
    @TempDir
    lateinit var root: Path

    private fun write(path: String, content: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    @Test
    fun `windows and macos are what java reports`() {
        write("etc/os-release", "PRETTY_NAME=\"Fedora Linux 44 (Workstation Edition)\"\n")
        val os = OperatingSystem(root)
        assertEquals("Windows 11 10.0 amd64", os.describe("Windows 11", "10.0", "amd64"))
        assertEquals("Mac OS X 15.5 aarch64", os.describe("Mac OS X", "15.5", "aarch64"))
    }

    @Test
    fun `linux names its distribution first`() {
        write("etc/os-release", "NAME=\"Fedora Linux\"\nPRETTY_NAME=\"Fedora Linux 44 (Workstation Edition)\"\nID=fedora\n")
        assertEquals(
            "Fedora Linux 44 (Workstation Edition); Linux 7.2.11-200.fc44.x86_64 amd64",
            OperatingSystem(root).describe("Linux", "7.2.11-200.fc44.x86_64", "amd64")
        )
    }

    @Test
    fun `flatpak host comes before the runtime`() {
        // What Prism Launcher's Flatpak sees on Fedora.
        write("etc/os-release", "PRETTY_NAME=\"KDE Flatpak runtime\"\n")
        write("run/host/os-release", "PRETTY_NAME=\"Ubuntu 24.04.3 LTS\"\n")
        assertEquals("Ubuntu 24.04.3 LTS", OperatingSystem(root).distribution())
    }

    @Test
    fun `usr lib is the fallback and nothing is empty`() {
        assertEquals("Linux 6.8.0 amd64", OperatingSystem(root).describe("Linux", "6.8.0", "amd64"))
        write("usr/lib/os-release", "PRETTY_NAME='Arch Linux'\n")
        assertEquals("Arch Linux", OperatingSystem(root).distribution())
    }

    @Test
    fun `quotes and escapes are removed`() {
        assertEquals("Debian GNU/Linux 13 (trixie)", OperatingSystem.prettyName("PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\""))
        assertEquals("NixOS", OperatingSystem.prettyName("PRETTY_NAME=NixOS"))
        assertEquals("a \"b\" \\c", OperatingSystem.prettyName("PRETTY_NAME=\"a \\\"b\\\" \\\\c\""))
        assertEquals("", OperatingSystem.prettyName("NAME=\"Gentoo\"\n"))
    }
}
