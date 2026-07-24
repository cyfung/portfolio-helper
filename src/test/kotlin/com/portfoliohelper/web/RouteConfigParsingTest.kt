package com.portfoliohelper.web

import com.portfoliohelper.service.DerivedTargetScaleFunction
import com.portfoliohelper.service.HysteresisStairsFallMode
import com.portfoliohelper.service.HysteresisStairsReferenceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class RouteConfigParsingTest {
    @Test
    fun parseCashEntriesPreservesPortfolioReferenceMultiplier() {
        val rows = buildJsonArray {
            add(buildJsonObject {
                put("label", JsonPrimitive("Final L - SSO"))
                put("currency", JsonPrimitive("P"))
                put("amount", JsonPrimitive(0.8125))
                put("marginFlag", JsonPrimitive(true))
                put("portfolioRef", JsonPrimitive("final-l-sso"))
            })
        }
        val cash = rows.parseCashEntries().single()
        assertEquals("P", cash.currency)
        assertEquals(0.8125, cash.amount)
        assertEquals("final-l-sso", cash.portfolioRef)
        assertEquals(true, cash.marginFlag)
    }

    @Test
    fun parseDerivedTargetScaleConfigKeepsHysteresisStepFunction() {
        val config = parseDerivedTargetScaleConfig(buildJsonObject {
            put("function", JsonPrimitive("HYSTERESIS_STEP"))
            put("referenceLower", JsonPrimitive(0.50))
            put("referenceUpper", JsonPrimitive(0.70))
            put("targetLower", JsonPrimitive(0.20))
            put("targetUpper", JsonPrimitive(1.00))
            put("stepBaseTarget", JsonPrimitive(0.95))
        })
        assertEquals(DerivedTargetScaleFunction.HYSTERESIS_STEP, config.function)
        assertEquals(0.20, config.targetLower)
        assertEquals(0.95, config.stepBaseTarget)
    }

    @Test
    fun parseDerivedTargetScaleConfigKeepsHysteresisStairsVariants() {
        val stairs = parseDerivedTargetScaleConfig(buildJsonObject {
            put("function", JsonPrimitive("HYSTERESIS_STAIRS"))
            put("targetUpper", JsonPrimitive(1.00))
            put("stepBaseTarget", JsonPrimitive(0.95))
            put("hysteresisStairsReferenceMode", JsonPrimitive("BUY_LOW_INTENTION"))
        })
        assertEquals(DerivedTargetScaleFunction.HYSTERESIS_STAIRS, stairs.function)
        assertEquals(1.00, stairs.targetUpper)
        assertEquals(0.95, stairs.stepBaseTarget)
        assertEquals(HysteresisStairsReferenceMode.BUY_LOW_INTENTION, stairs.hysteresisStairsReferenceMode)

        val fixedTarget = parseDerivedTargetScaleConfig(buildJsonObject {
            put("function", JsonPrimitive("HYSTERESIS_STAIRS_FIXED_TARGET_REF"))
            put("hysteresisStairsReferenceMode", JsonPrimitive("BUY_LOW_INTENTION"))
            put("hysteresisStairsFallMode", JsonPrimitive("MOMENTUM_WITH_RECOVERY"))
            put("momentumLookbackMonths", JsonPrimitive(3))
        })
        assertEquals(DerivedTargetScaleFunction.HYSTERESIS_STAIRS_FIXED_TARGET_REF, fixedTarget.function)
        assertEquals(HysteresisStairsReferenceMode.BUY_LOW_INTENTION, fixedTarget.hysteresisStairsReferenceMode)
        assertEquals(HysteresisStairsFallMode.MOMENTUM_WITH_RECOVERY, fixedTarget.hysteresisStairsFallMode)
        assertEquals(3, fixedTarget.momentumLookbackMonths)
    }

    @Test
    fun parseDerivedTargetScaleConfigMigratesLegacyHysteresisVariants() {
        val momentum = parseDerivedTargetScaleConfig(buildJsonObject {
            put("function", JsonPrimitive("HYSTERESIS_STAIRS_MOMENTUM"))
            put("momentumLookbackMonths", JsonPrimitive(3))
        })
        assertEquals(DerivedTargetScaleFunction.HYSTERESIS_STAIRS, momentum.function)
        assertEquals(HysteresisStairsFallMode.MOMENTUM, momentum.hysteresisStairsFallMode)
        assertEquals(3, momentum.momentumLookbackMonths)

        val recovery = parseDerivedTargetScaleConfig(buildJsonObject {
            put("function", JsonPrimitive("HYSTERESIS_STAIRS"))
            put("hysteresisStairsFallMode", JsonPrimitive("MOMENTUM_WITH_RECOVERY"))
            put("momentumLookbackMonths", JsonPrimitive(3))
        })
        assertEquals(HysteresisStairsFallMode.MOMENTUM_WITH_RECOVERY, recovery.hysteresisStairsFallMode)

        val reset = parseDerivedTargetScaleConfig(buildJsonObject {
            put("function", JsonPrimitive("HYSTERESIS_STAIRS_REF_BL_RESET"))
        })
        assertEquals(DerivedTargetScaleFunction.HYSTERESIS_STAIRS_REF_BL_RESET, reset.function)
    }

    @Test
    fun firstPortfolioSettingsPreserveTheOtherAnalysisSettings() {
        val existing = buildJsonObject {
            put("fromDate", JsonPrimitive("2000-01-01"))
            put("startingBalance", JsonPrimitive(10000))
            put("drawdownConfigs", JsonPrimitive("old-market"))
            put("strategies", JsonPrimitive("old-rebalance"))
            put("strategyStates", JsonPrimitive("rebalance-state"))
        }
        val rebalanceIncoming = buildJsonObject {
            put("fromDate", JsonPrimitive("2010-01-01"))
            put("startingBalance", JsonPrimitive(25000))
            put("strategies", JsonPrimitive("new-rebalance"))
            put("portfolio", JsonPrimitive("not-persisted-here"))
            put("saveSettings", JsonPrimitive(true))
        }
        val rebalanceMerged = mergedFirstPortfolioSettings(
            existing,
            rebalanceIncoming,
            setOf("startingBalance", "cashflow", "strategies", "strategyStates", "includeActionDiagnostics"),
        )
        val common = mergedCommonScenarioSettings(existing, rebalanceIncoming)
        assertEquals("2010-01-01", common["fromDate"]?.jsonPrimitive?.content)
        assertEquals(25000, common["startingBalance"]?.jsonPrimitive?.int)
        assertEquals("old-market", rebalanceMerged["drawdownConfigs"]?.jsonPrimitive?.content)
        assertEquals("new-rebalance", rebalanceMerged["strategies"]?.jsonPrimitive?.content)
        assertNull(rebalanceMerged["portfolio"])
        assertNull(rebalanceMerged["saveSettings"])

        val marketIncoming = buildJsonObject {
            put("drawdownConfigs", JsonPrimitive("new-market"))
            put("portfolios", JsonPrimitive("not-persisted-here"))
        }
        val marketMerged = mergedFirstPortfolioSettings(
            existing,
            marketIncoming,
            setOf("drawdownConfigs", "referenceSource", "referenceTicker", "interestMode", "annualSpread", "fixedAnnualRate"),
        )
        assertEquals("old-rebalance", marketMerged["strategies"]?.jsonPrimitive?.content)
        assertEquals("rebalance-state", marketMerged["strategyStates"]?.jsonPrimitive?.content)
        assertEquals("new-market", marketMerged["drawdownConfigs"]?.jsonPrimitive?.content)
        assertNull(marketMerged["portfolios"])
    }

    @Test
    fun mergedBacktestSettingsKeepsRebalanceStrategySettings() {
        val existing = buildJsonObject {
            put("fromDate", JsonPrimitive("2000-01-01"))
            put("startingBalance", JsonPrimitive(10000))
            put("strategies", JsonPrimitive("rebalance"))
            put("strategyStates", JsonPrimitive("rebalance-state"))
            put("includeActionDiagnostics", JsonPrimitive(true))
        }
        val incoming = buildJsonObject {
            put("fromDate", JsonPrimitive("2010-01-01"))
            put("toDate", JsonPrimitive("2024-12-31"))
            put("startingBalance", JsonPrimitive(25000))
            put("cashflow", JsonPrimitive("new-cashflow"))
            put("portfolios", JsonPrimitive("not-persisted-here"))
            put("saveSettings", JsonPrimitive(true))
        }
        val merged = mergedBacktestSettings(existing, incoming)
        val common = mergedCommonScenarioSettings(existing, incoming)
        assertEquals("2010-01-01", common["fromDate"]?.jsonPrimitive?.content)
        assertEquals("2024-12-31", common["toDate"]?.jsonPrimitive?.content)
        assertEquals(25000, common["startingBalance"]?.jsonPrimitive?.int)
        assertEquals("new-cashflow", common["cashflow"]?.jsonPrimitive?.content)
        assertEquals("rebalance", merged["strategies"]?.jsonPrimitive?.content)
        assertEquals("rebalance-state", merged["strategyStates"]?.jsonPrimitive?.content)
        assertEquals(true, merged["includeActionDiagnostics"]?.jsonPrimitive?.boolean)
        assertNull(merged["portfolios"])
        assertNull(merged["saveSettings"])
    }
}
