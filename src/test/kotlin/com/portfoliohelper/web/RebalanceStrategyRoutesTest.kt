package com.portfoliohelper.web

import com.portfoliohelper.service.DerivedTargetScaleFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class RebalanceStrategyRoutesTest {
    @Test
    fun parseDerivedTargetScaleConfigKeepsHysteresisStepFunction() {
        val config = parseDerivedTargetScaleConfig(
            buildJsonObject {
                put("function", JsonPrimitive("HYSTERESIS_STEP"))
                put("referenceLower", JsonPrimitive(0.50))
                put("referenceUpper", JsonPrimitive(0.70))
                put("targetLower", JsonPrimitive(0.20))
                put("targetUpper", JsonPrimitive(1.00))
                put("stepBaseTarget", JsonPrimitive(0.95))
            }
        )

        assertEquals(DerivedTargetScaleFunction.HYSTERESIS_STEP, config.function)
        assertEquals(0.20, config.targetLower)
        assertEquals(0.95, config.stepBaseTarget)
    }

    @Test
    fun parseDerivedTargetScaleConfigKeepsHysteresisStairsFunction() {
        val config = parseDerivedTargetScaleConfig(
            buildJsonObject {
                put("function", JsonPrimitive("HYSTERESIS_STAIRS"))
                put("targetUpper", JsonPrimitive(1.00))
                put("stepBaseTarget", JsonPrimitive(0.95))
            }
        )

        assertEquals(DerivedTargetScaleFunction.HYSTERESIS_STAIRS, config.function)
        assertEquals(1.00, config.targetUpper)
        assertEquals(0.95, config.stepBaseTarget)
    }

    @Test
    fun parseDerivedTargetScaleConfigKeepsHysteresisStairsRefBuyLowResetFunction() {
        val config = parseDerivedTargetScaleConfig(
            buildJsonObject {
                put("function", JsonPrimitive("HYSTERESIS_STAIRS_REF_BL_RESET"))
            }
        )

        assertEquals(DerivedTargetScaleFunction.HYSTERESIS_STAIRS_REF_BL_RESET, config.function)
    }

    @Test
    fun mergedFirstPortfolioSettingsKeepsMarketTimingSettingsWhenRebalanceStrategySaves() {
        val existing = buildJsonObject {
            put("fromDate", JsonPrimitive("2000-01-01"))
            put("drawdownConfigs", JsonPrimitive("5-1, 10-1"))
            put("referenceSource", JsonPrimitive("TICKER"))
            put("referenceTicker", JsonPrimitive("SPY"))
            put("strategies", JsonPrimitive("old-rebalance"))
        }
        val incoming = buildJsonObject {
            put("fromDate", JsonPrimitive("2010-01-01"))
            put("startingBalance", JsonPrimitive(25000))
            put("strategies", JsonPrimitive("new-rebalance"))
            put("portfolio", JsonPrimitive("not-persisted-here"))
            put("saveSettings", JsonPrimitive(true))
        }

        val merged = mergedFirstPortfolioSettings(
            existing,
            incoming,
            setOf("startingBalance", "cashflow", "strategies", "strategyStates", "includeActionDiagnostics"),
        )

        assertEquals("2010-01-01", merged["fromDate"]?.jsonPrimitive?.content)
        assertEquals("5-1, 10-1", merged["drawdownConfigs"]?.jsonPrimitive?.content)
        assertEquals("TICKER", merged["referenceSource"]?.jsonPrimitive?.content)
        assertEquals("SPY", merged["referenceTicker"]?.jsonPrimitive?.content)
        assertEquals("new-rebalance", merged["strategies"]?.jsonPrimitive?.content)
        assertEquals(25000, merged["startingBalance"]?.jsonPrimitive?.int)
        assertNull(merged["portfolio"])
        assertNull(merged["saveSettings"])
    }

    @Test
    fun mergedFirstPortfolioSettingsKeepsRebalanceStrategySettingsWhenMarketTimingSaves() {
        val existing = buildJsonObject {
            put("toDate", JsonPrimitive("2020-12-31"))
            put("startingBalance", JsonPrimitive(10000))
            put("strategies", JsonPrimitive("rebalance"))
            put("strategyStates", JsonPrimitive("rebalance-state"))
            put("drawdownConfigs", JsonPrimitive("old-market"))
        }
        val incoming = buildJsonObject {
            put("toDate", JsonPrimitive("2024-12-31"))
            put("drawdownConfigs", JsonPrimitive("15-0, 20-1"))
            put("referenceSource", JsonPrimitive("PORTFOLIO"))
            put("portfolios", JsonPrimitive("not-persisted-here"))
        }

        val merged = mergedFirstPortfolioSettings(
            existing,
            incoming,
            setOf(
                "drawdownConfigs",
                "drawdownPcts",
                "drawdownPct",
                "referenceSource",
                "referenceTicker",
                "interestMode",
                "annualSpread",
                "fixedAnnualRate",
            ),
        )

        assertEquals("2024-12-31", merged["toDate"]?.jsonPrimitive?.content)
        assertEquals(10000, merged["startingBalance"]?.jsonPrimitive?.int)
        assertEquals("rebalance", merged["strategies"]?.jsonPrimitive?.content)
        assertEquals("rebalance-state", merged["strategyStates"]?.jsonPrimitive?.content)
        assertEquals("15-0, 20-1", merged["drawdownConfigs"]?.jsonPrimitive?.content)
        assertEquals("PORTFOLIO", merged["referenceSource"]?.jsonPrimitive?.content)
        assertNull(merged["portfolios"])
    }
}
