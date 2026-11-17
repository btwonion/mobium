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
            offload = OffloadConfig(enabled = false, items = false, excludedEntities = mutableListOf("minecraft:zombie"))
        )
        assertEquals(config, json.decodeFromString<Config>(json.encodeToString(config)))
    }

    @Test
    fun `unknown keys fail so that Konfig routes files of another format to the migration`() {
        assertThrows<SerializationException> {
            json.decodeFromString<Config>("""{"enabled": true, "removedSetting": 1}""")
        }
    }

    @Test
    fun `a file of another format fails to decode and gets the defaults`() {
        val text = """{"enabled": false, "parts": {"enabled": false, "items": false, "excludedEntities": []}}"""
        assertThrows<SerializationException> { json.decodeFromString<Config>(text) }
        assertNull(migrate(json, json.parseToJsonElement(text), 6))
    }

    @Test
    fun `defaults turn everything on`() {
        val config = Config()
        assertTrue(config.enabled && config.cache.enabled && config.offload.enabled && config.offload.items)
        assertEquals(128, config.cache.memoryBudgetMb)
        assertTrue(config.cache.types.switches().all { it.second.get() })
    }

    @Test
    fun `deep copies are independent`() {
        val original = Config()
        val copy = original.deepCopy()
        copy.cache.types.chests = false
        copy.offload.excludedEntities += "minecraft:cow"
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
