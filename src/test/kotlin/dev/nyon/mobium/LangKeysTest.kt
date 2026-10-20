package dev.nyon.mobium

import dev.nyon.mobium.config.CacheTypes
import dev.nyon.mobium.config.LodPreset
import dev.nyon.mobium.core.Module
import dev.nyon.mobium.core.OffReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LangKeysTest {
    private fun keys(language: String): Set<String> {
        val text = checkNotNull(javaClass.getResource("/assets/mobium/lang/$language.json")) { "$language.json missing" }
            .readText()
        return Json.parseToJsonElement(text).jsonObject.keys
    }

    @Test
    fun `english and german translations have the same keys`() {
        assertEquals(keys("en_us"), keys("de_de"))
    }

    @Test
    fun `keys built from enums and switches exist`() {
        val keys = keys("en_us")
        val built = Module.entries.map { it.translationKey } +
            OffReason.entries.flatMap { listOf(it.translationKey, "${it.translationKey}.tooltip") } +
            LodPreset.entries.map { "mobium.lod_preset.${it.name.lowercase()}" } +
            CacheTypes().switches().flatMap { (key, _) ->
                listOf("mobium.settings.type.$key", "mobium.settings.type.$key.tooltip")
            }
        val missing = built.filter { it !in keys }
        assertTrue(missing.isEmpty()) { "Missing translations: $missing" }
    }
}
