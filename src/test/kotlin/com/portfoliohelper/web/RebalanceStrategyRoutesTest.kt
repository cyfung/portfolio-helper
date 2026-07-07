package com.portfoliohelper.web

import com.portfoliohelper.service.DerivedTargetScaleFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class RebalanceStrategyRoutesTest {
    @Test
    fun resolveTickerWeightsScalesNonFullChildPortfolioToParentWeight() {
        val savedConfigs = mapOf(
            "Child" to buildJsonObject {
                put("tickers", buildJsonArray {
                    add(buildJsonObject {
                        put("ticker", JsonPrimitive("AAA"))
                        put("weight", JsonPrimitive(40))
                    })
                    add(buildJsonObject {
                        put("ticker", JsonPrimitive("BBB"))
                        put("weight", JsonPrimitive(10))
                    })
                })
            },
        )
        val rows = buildJsonArray {
            add(buildJsonObject {
                put("ticker", JsonPrimitive("Child"))
                put("portfolioRef", JsonPrimitive("Child"))
                put("isPortfolioRef", JsonPrimitive(true))
                put("weight", JsonPrimitive(20))
            })
        }

        val resolved = resolveTickerWeights(rows, savedConfigs, emptyList()).associate { it.ticker to it.weight }

        assertEquals(16.0, resolved["AAA"])
        assertEquals(4.0, resolved["BBB"])
        assertEquals(20.0, resolved.values.sum())
    }

    @Test
    fun resolveTickerWeightsKeepsChildReferenceTotalExactAfterFractionalScaling() {
        val savedConfigs = mapOf(
            "Child" to buildJsonObject {
                put("tickers", buildJsonArray {
                    repeat(3) { index ->
                        add(buildJsonObject {
                            put("ticker", JsonPrimitive("T$index"))
                            put("weight", JsonPrimitive(1))
                        })
                    }
                })
            },
        )
        val rows = buildJsonArray {
            add(buildJsonObject {
                put("ticker", JsonPrimitive("Child"))
                put("portfolioRef", JsonPrimitive("Child"))
                put("isPortfolioRef", JsonPrimitive(true))
                put("weight", JsonPrimitive(100))
            })
        }

        val resolved = resolveTickerWeights(rows, savedConfigs, emptyList())

        assertEquals(100.0, resolved.sumOf { it.weight })
    }

    @Test
    fun resolveTickerWeightsUsesNegativeDummyInChildReferenceScaling() {
        val savedConfigs = mapOf(
            "Child" to buildJsonObject {
                put("tickers", buildJsonArray {
                    add(buildJsonObject {
                        put("ticker", JsonPrimitive("AAA"))
                        put("weight", JsonPrimitive(100))
                    })
                    add(buildJsonObject {
                        put("ticker", JsonPrimitive("DUMMY"))
                        put("weight", JsonPrimitive(-50))
                    })
                })
            },
        )
        val rows = buildJsonArray {
            add(buildJsonObject {
                put("ticker", JsonPrimitive("Child"))
                put("portfolioRef", JsonPrimitive("Child"))
                put("isPortfolioRef", JsonPrimitive(true))
                put("weight", JsonPrimitive(50))
            })
            add(buildJsonObject {
                put("ticker", JsonPrimitive("CCC"))
                put("weight", JsonPrimitive(50))
            })
        }

        val resolved = resolveTickerWeights(rows, savedConfigs, emptyList()).associate { it.ticker to it.weight }

        assertEquals(100.0, resolved["AAA"])
        assertEquals(-50.0, resolved["DUMMY"])
        assertEquals(50.0, resolved["CCC"])
        assertEquals(100.0, resolved.values.sum())
    }

    @Test
    fun resolveTickerWeightsKeepsNegativeChildTickerAfterReferenceScaling() {
        val savedConfigs = mapOf(
            "Child" to buildJsonObject {
                put("tickers", buildJsonArray {
                    add(buildJsonObject {
                        put("ticker", JsonPrimitive("AAA"))
                        put("weight", JsonPrimitive(100))
                    })
                    add(buildJsonObject {
                        put("ticker", JsonPrimitive("BBB"))
                        put("weight", JsonPrimitive(-50))
                    })
                })
            },
        )
        val rows = buildJsonArray {
            add(buildJsonObject {
                put("ticker", JsonPrimitive("Child"))
                put("portfolioRef", JsonPrimitive("Child"))
                put("isPortfolioRef", JsonPrimitive(true))
                put("weight", JsonPrimitive(50))
            })
            add(buildJsonObject {
                put("ticker", JsonPrimitive("CCC"))
                put("weight", JsonPrimitive(50))
            })
        }

        val resolved = resolveTickerWeights(rows, savedConfigs, emptyList()).associate { it.ticker to it.weight }

        assertEquals(100.0, resolved["AAA"])
        assertEquals(-50.0, resolved["BBB"])
        assertEquals(50.0, resolved["CCC"])
        assertEquals(100.0, resolved.values.sum())
    }

    @Test
    fun resolveTickerWeightsKeepsFrontendExpandedSwapRows() {
        val rows = buildJsonArray {
            add(buildJsonObject {
                put("ticker", JsonPrimitive("AAA"))
                put("weight", JsonPrimitive(-50))
            })
            add(buildJsonObject {
                put("ticker", JsonPrimitive("BBB"))
                put("weight", JsonPrimitive(75))
            })
            add(buildJsonObject {
                put("ticker", JsonPrimitive("DUMMY"))
                put("weight", JsonPrimitive(25))
            })
            add(buildJsonObject {
                put("ticker", JsonPrimitive("CCC"))
                put("weight", JsonPrimitive(50))
            })
        }

        val resolved = resolveTickerWeights(rows, emptyMap(), emptyList()).associate { it.ticker to it.weight }

        assertEquals(-50.0, resolved["AAA"])
        assertEquals(75.0, resolved["BBB"])
        assertEquals(25.0, resolved["DUMMY"])
        assertEquals(50.0, resolved["CCC"])
        assertEquals(100.0, resolved.values.sum())
    }

    @Test
    fun resolveTickerWeightsKeepsNetShortChildReferenceForParentMerge() {
        val savedConfigs = mapOf(
            "Child" to buildJsonObject {
                put("tickers", buildJsonArray {
                    add(buildJsonObject {
                        put("ticker", JsonPrimitive("AAA"))
                        put("weight", JsonPrimitive(-100))
                    })
                })
            },
        )
        val rows = buildJsonArray {
            add(buildJsonObject {
                put("ticker", JsonPrimitive("Child"))
                put("portfolioRef", JsonPrimitive("Child"))
                put("isPortfolioRef", JsonPrimitive(true))
                put("weight", JsonPrimitive(50))
            })
        }

        val resolved = resolveTickerWeights(rows, savedConfigs, emptyList()).associate { it.ticker to it.weight }

        assertEquals(-50.0, resolved["AAA"])
        assertEquals(-50.0, resolved.values.sum())
    }

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

    @Test
    fun mergedBacktestSettingsKeepsRebalanceStrategySettingsWhenBacktestSaves() {
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

        assertEquals("2010-01-01", merged["fromDate"]?.jsonPrimitive?.content)
        assertEquals("2024-12-31", merged["toDate"]?.jsonPrimitive?.content)
        assertEquals(25000, merged["startingBalance"]?.jsonPrimitive?.int)
        assertEquals("new-cashflow", merged["cashflow"]?.jsonPrimitive?.content)
        assertEquals("rebalance", merged["strategies"]?.jsonPrimitive?.content)
        assertEquals("rebalance-state", merged["strategyStates"]?.jsonPrimitive?.content)
        assertEquals(true, merged["includeActionDiagnostics"]?.jsonPrimitive?.boolean)
        assertNull(merged["portfolios"])
        assertNull(merged["saveSettings"])
    }
}
