package dev.nyon.mobium.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConfigFormatTest {
    // Mirrors the serializer Konfig builds in config(): pretty print and defaults on, nothing else.
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    @Test
    fun `config survives a round trip`() {
        val config = Config(enabled = false)
        assertEquals(config, json.decodeFromString<Config>(json.encodeToString(config)))
    }

    @Test
    fun `unknown keys fail so that Konfig routes old files to the migration`() {
        assertThrows<SerializationException> {
            json.decodeFromString<Config>("""{"enabled": true, "removedSetting": 1}""")
        }
    }

    @Test
    fun `unknown versions fall back to defaults`() {
        assertNull(migrate(json, JsonObject(emptyMap()), null))
        assertNull(migrate(json, JsonObject(emptyMap()), CONFIG_VERSION + 1))
    }
}
