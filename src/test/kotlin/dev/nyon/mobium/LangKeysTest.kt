package dev.nyon.mobium

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
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
}
