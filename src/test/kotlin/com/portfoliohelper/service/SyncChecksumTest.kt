package com.portfoliohelper.service

import com.portfoliohelper.util.appJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
