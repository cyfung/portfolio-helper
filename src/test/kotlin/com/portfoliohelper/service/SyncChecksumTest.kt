package com.portfoliohelper.service

import com.portfoliohelper.util.appJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncChecksumTest {
    @Test
    fun `Android sync preserves legacy stock payload for manually managed holdings`() {
        val stock = BackupStock("SSO", 10.0, manualQty = true).toAndroidSyncStock()
        val response = AllSyncResponse(
            portfolios = listOf(
                PortfolioSyncEntry(
                    serialId = 1,
                    name = "Portfolio",
                    slug = "portfolio",
                    stocks = listOf(stock),
                    cash = emptyList(),
                )
            ),
            checksum = "4e29170116bceb21149f0e654fd0d28a7423bad29fd788ce510ad0646a075296",
        )
        val payload = appJson.encodeToString(AllSyncResponse.serializer(), response)

        assertEquals(
            response.checksum,
            computeSyncChecksum(response.portfolios),
        )
        assertFalse("manualQty" in payload)
    }

    @Test
    fun `Android sync carries flexible weight mappings without changing its checksum`() {
        val mappings = """[{"left":"UPRO","right":"3 VOO"}]"""
        val entry = PortfolioSyncEntry(
            serialId = 7,
            name = "Flexible",
            slug = "flexible",
            stocks = listOf(AndroidSyncStock("UPRO", 2.0)),
            cash = emptyList(),
            flexibleWeightMappings = mappings,
        )
        val payload = appJson.encodeToString(
            AllSyncResponse.serializer(),
            AllSyncResponse(listOf(entry), computeSyncChecksum(listOf(entry))),
        )

        assertTrue("\"flexibleWeightMappings\":\"" in payload)
        assertEquals(
            "08be6054d0152a015f398a5eeab9b05081bb3105400cb4b72c057d2129710cfa",
            computeSyncChecksum(listOf(entry)),
        )
    }
}
