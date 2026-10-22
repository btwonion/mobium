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
            cache = CacheConfig(memoryBudgetMb = 256, types = CacheTypes(signs = false)),
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
    fun `a version 3 file keeps its settings and loses the prototype and beds switches`() {
        val tree = json.parseToJsonElement(
            """{"enabled": false, "cache": {"memoryBudgetMb": 64, "prototype": true, "types": {"beds": false, "signs": false}}}"""
        )
        val expected = Config(enabled = false, cache = CacheConfig(memoryBudgetMb = 64, types = CacheTypes(signs = false)))
        assertEquals(expected, migrate(json, tree, 3))
    }

    @Test
    fun `a version 3 file without cache settings gets the defaults`() {
        assertEquals(Config(enabled = false), migrate(json, json.parseToJsonElement("""{"enabled": false}"""), 3))
    }

    @Test
    fun `the old prototype key no longer decodes, which routes version 3 files to the migration`() {
        assertThrows<SerializationException> {
            json.decodeFromString<Config>("""{"enabled": false, "cache": {"prototype": true}}""")
        }
    }

    @Test
    fun `version 1 keeps the global switch and gets the default cache settings`() {
        val tree = json.parseToJsonElement("""{"enabled": false}""")
        assertEquals(Config(enabled = false), migrate(json, tree, 1))
    }

    @Test
    fun `version 2 keeps the global switch only`() {
        val tree = json.parseToJsonElement("""{"enabled": false, "cache": {"prototype": true, "broken": 1}}""")
        assertEquals(Config(enabled = false), migrate(json, tree, 2))
    }

    @Test
    fun `defaults follow the plan`() {
        val config = Config()
        assertTrue(config.enabled && config.cache.enabled && config.parts.enabled)
        assertEquals(LodPreset.OFF, config.lod.preset)
        assertEquals(128, config.cache.memoryBudgetMb)
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
