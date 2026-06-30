package com.portfoliohelper.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BacktestModelsTest {
    @Test
    fun mergeWeightsKeepsNormalizedWeightsSumExactAfterDuplicateMerging() {
        val portfolio = PortfolioConfig(
            label = "test",
            tickers = listOf(
                TickerWeight("AAA", 1.0),
                TickerWeight("BBB", 1.0),
                TickerWeight("AAA", 1.0),
            ),
            rebalanceStrategy = RebalanceStrategy.NONE,
            marginStrategies = emptyList(),
        )

        val (_, targetWeights) = portfolio.mergeWeights()

        assertEquals(1.0, targetWeights.values.sum())
        assertTrue(kotlin.math.abs((targetWeights["AAA"] ?: 0.0) - 2.0 / 3.0) < 1e-15)
        assertTrue(kotlin.math.abs((targetWeights["BBB"] ?: 0.0) - 1.0 / 3.0) < 1e-15)
    }
}
