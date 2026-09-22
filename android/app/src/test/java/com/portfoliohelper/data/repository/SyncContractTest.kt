package com.portfoliohelper.data.repository

import com.portfoliohelper.data.model.Portfolio
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncContractTest {
    @Test
    fun `sync payload accepts both flexible mappings and legacy portfolios`() {
        val response = decodeAllSyncResponse(
            """
            {
              "portfolios": [
                {"serialId":1,"name":"Legacy","slug":"legacy","stocks":[],"cash":[]},
                {"serialId":2,"name":"Flexible","slug":"flexible","stocks":[],"cash":[],
                 "flexibleWeightMappings":"[{\"left\":\"UPRO\",\"right\":\"3 VOO\"}]"}
              ],
              "checksum":""
            }
            """.trimIndent(),
        )

        assertEquals("", response.portfolios[0].flexibleWeightMappings)
        assertEquals(
            """[{"left":"UPRO","right":"3 VOO"}]""",
            response.portfolios[1].flexibleWeightMappings,
        )
    }

    @Test
    fun `sync clears flexible preference for removed invalid or replaced portfolios`() {
        val previous = listOf(
            Portfolio(1, "Kept", "kept", "[]"),
            Portfolio(2, "Removed", "removed", "[]"),
            Portfolio(3, "Replaced", "old-slug", "[]"),
            Portfolio(4, "Invalidated", "invalidated", "[]"),
        )
        val valid = """[{"left":"A","right":"B"}]"""
        val synced = listOf(
            PortfolioSyncEntry(1, "Kept", "kept", emptyList(), emptyList(), valid),
            PortfolioSyncEntry(3, "Replacement", "new-slug", emptyList(), emptyList(), valid),
            PortfolioSyncEntry(4, "Invalidated", "invalidated", emptyList(), emptyList(), "not json"),
        )

        assertEquals(setOf(2, 3, 4), flexiblePreferenceIdsToClear(previous, synced))
    }
}
