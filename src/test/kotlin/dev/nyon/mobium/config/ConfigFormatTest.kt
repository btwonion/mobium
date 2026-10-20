package dev.nyon.mobium.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
        val config = Config(
            enabled = false,
            cache = CacheConfig(memoryBudgetMb = 256, types = CacheTypes(signs = false), prototype = true),
            parts = PartsConfig(enabled = false, excludedEntities = mutableListOf("minecraft:zombie")),
            lod = LodConfig(preset = LodPreset.SUBTLE)
        )
        assertEquals(config, json.decodeFromString<Config>(json.encodeToString(config)))
    }

    @Test
    fun `unknown keys fail so that Konfig routes old files to the migration`() {
        assertThrows<SerializationException> {
            json.decodeFromString<Config>("""{"enabled": true, "removedSetting": 1}""")
        }
    }

    @Test
    fun `a version 2 file decodes as it is, with defaults for the new settings`() {
        val config = json.decodeFromString<Config>("""{"enabled": false, "cache": {"prototype": true}}""")
        assertEquals(Config(enabled = false, cache = CacheConfig(prototype = true)), config)
    }

    @Test
    fun `version 1 keeps the global switch and gets the default cache settings`() {
        val tree = json.parseToJsonElement("""{"enabled": false}""")
        assertEquals(Config(enabled = false), migrate(json, tree, 1))
    }

    @Test
    fun `version 2 keeps the global switch and the prototype when the rest does not decode`() {
        val tree = json.parseToJsonElement("""{"enabled": false, "cache": {"prototype": true, "broken": 1}}""")
        assertEquals(Config(enabled = false, cache = CacheConfig(prototype = true)), migrate(json, tree, 2))
    }

    @Test
    fun `defaults follow the plan`() {
        val config = Config()
        assertTrue(config.enabled && config.cache.enabled && config.parts.enabled)
        assertEquals(LodPreset.OFF, config.lod.preset)
        assertEquals(128, config.cache.memoryBudgetMb)
        assertEquals(false, config.cache.prototype)
        assertTrue(config.cache.types.switches().all { it.second.get() })
    }

    @Test
    fun `deep copies are independent`() {
        val original = Config()
        val copy = original.deepCopy()
        copy.cache.types.chests = false
        copy.parts.excludedEntities += "minecraft:cow"
        copy.lod.excludedEntities += "minecraft:cow"
        assertNotEquals(original, copy)
        assertEquals(Config(), original)
    }

    @Test
    fun `type switches cover every type field once`() {
        val keys = CacheTypes().switches().map { it.first }
        assertEquals(keys.toSet().size, keys.size)
        assertEquals(CacheTypes.serializer().descriptor.elementsCount, keys.size)
    }

    @Test
    fun `unknown versions fall back to defaults`() {
        assertNull(migrate(json, JsonObject(emptyMap()), null))
        assertNull(migrate(json, JsonObject(emptyMap()), CONFIG_VERSION + 1))
    }
}
