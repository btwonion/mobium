package dev.nyon.mobium.bench

import java.nio.file.Files
import java.nio.file.Path

/**
 * The operating system as benchmark files record it: Java's `os.name`, `os.version` (the kernel release on Linux) and
 * `os.arch`, and on Linux first the distribution's `PRETTY_NAME` from os-release. Under Flatpak (Prism Launcher's
 * package, for one) `/etc/os-release` names the runtime, so the host's copy at `/run/host/os-release` comes first.
 *
 * Pure file reading, so the unit tests run it against a fake root.
 */
class OperatingSystem(private val root: Path) {
    /** `<PRETTY_NAME>; <name> <version> <arch>` on Linux with an os-release file, else `<name> <version> <arch>`. */
    fun describe(name: String, version: String, arch: String): String {
        val java = listOf(name, version, arch).filter(String::isNotBlank).joinToString(" ")
        if (!name.startsWith("Linux")) return java
        val distribution = distribution()
        return if (distribution.isEmpty()) java else "$distribution; $java"
    }

    /** `PRETTY_NAME` of the first os-release file that can be read, or empty. */
    fun distribution(): String {
        for (file in OS_RELEASE) {
            val text = try {
                Files.readString(root.resolve(file))
            } catch (_: Exception) {
                continue
            }
            return prettyName(text)
        }
        return ""
    }

    companion object {
        /** The host's copy (Flatpak and other containers), then the order of the os-release specification. */
        private val OS_RELEASE = listOf("run/host/os-release", "etc/os-release", "usr/lib/os-release")

        /** This machine's, read once: nothing in it changes while the game runs. */
        val current: String by lazy {
            OperatingSystem(Path.of("/")).describe(
                System.getProperty("os.name") ?: "", System.getProperty("os.version") ?: "", System.getProperty("os.arch") ?: ""
            )
        }

        /** The `PRETTY_NAME` value of an os-release file, without its quotes and backslash escapes; empty if unset. */
        fun prettyName(osRelease: String): String {
            for (line in osRelease.lines()) {
                if (!line.startsWith("PRETTY_NAME=")) continue
                val value = line.substringAfter('=').trim()
                val quote = value.firstOrNull()
                if (value.length < 2 || (quote != '"' && quote != '\'') || value.last() != quote) return value
                val inner = value.substring(1, value.length - 1)
                return if (quote == '"') inner.replace(ESCAPE, "$1") else inner
            }
            return ""
        }

        private val ESCAPE = Regex("""\\(.)""")
    }
}
