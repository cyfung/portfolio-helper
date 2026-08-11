package com.portfoliohelper.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatedInstrumentManifestTest {
    @Test
    fun `declared simulated instruments have bundled resources`() {
        val manifestText = checkNotNull(javaClass.getResourceAsStream("/simulated-instruments.json")) {
            "Missing shared simulated-instruments.json"
        }.bufferedReader().use { it.readText() }
        val tickers = Json.parseToJsonElement(manifestText)
            .jsonObject.getValue("simulatedInstruments")
            .jsonArray.map { it.jsonPrimitive.content }

        assertEquals(tickers.distinct().sorted(), tickers, "Manifest tickers must be unique and sorted")
        assertTrue("EFFRX" !in tickers, "Internal EFFRX must not be user-facing")

        val resourceDir = File(checkNotNull(javaClass.getResource("/data/.ticker")) { "Missing bundled ticker resources" }.toURI())
        val resourceTickers = resourceDir.listFiles().orEmpty()
            .mapNotNull { Regex("^(.+)-\\d{4}-\\d{2}-\\d{2}\\.csv$").matchEntire(it.name)?.groupValues?.get(1) }
            .filter { it != "EFFRX" }
            .distinct()
            .sorted()
        assertEquals(tickers, resourceTickers, "Manifest must declare every user-facing bundled resource")
        tickers.forEach { ticker ->
            assertTrue(
                resourceDir.listFiles().orEmpty().any { it.name.matches(Regex("${Regex.escape(ticker)}-\\d{4}-\\d{2}-\\d{2}\\.csv")) },
                "No bundled simulated resource exists for $ticker",
            )
        }
    }
}
