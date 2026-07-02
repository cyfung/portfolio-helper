package com.portfoliohelper.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun mergeWeightsIgnoresDummyAndRenormalizesRemainingTickers() {
        val portfolio = PortfolioConfig(
            label = "test",
            tickers = listOf(
                TickerWeight("AAA", 35.0),
                TickerWeight("dummy", 30.0),
                TickerWeight("BBB", 35.0),
            ),
            rebalanceStrategy = RebalanceStrategy.NONE,
            marginStrategies = emptyList(),
        )

        val (tickers, targetWeights) = portfolio.mergeWeights()

        assertEquals(listOf("AAA", "BBB"), tickers)
        assertEquals(1.0, targetWeights.values.sum())
        assertTrue(kotlin.math.abs((targetWeights["AAA"] ?: 0.0) - 0.5) < 1e-15)
        assertTrue(kotlin.math.abs((targetWeights["BBB"] ?: 0.0) - 0.5) < 1e-15)
    }

    @Test
    fun mergeWeightsKeepsNegativeMergedTickerAsShortExposure() {
        val portfolio = PortfolioConfig(
            label = "test",
            tickers = listOf(
                TickerWeight("AAA", 100.0),
                TickerWeight("BBB", -20.0),
                TickerWeight("BBB", -30.0),
                TickerWeight("CCC", 50.0),
            ),
            rebalanceStrategy = RebalanceStrategy.NONE,
            marginStrategies = emptyList(),
        )

        val (tickers, targetWeights) = portfolio.mergeWeights()

        assertEquals(listOf("AAA", "BBB", "CCC"), tickers)
        assertEquals(1.0, targetWeights.values.sum())
        assertTrue(kotlin.math.abs((targetWeights["AAA"] ?: 0.0) - 1.0) < 1e-15)
        assertTrue(kotlin.math.abs((targetWeights["BBB"] ?: 0.0) - -0.5) < 1e-15)
        assertTrue(kotlin.math.abs((targetWeights["CCC"] ?: 0.0) - 0.5) < 1e-15)
    }

    @Test
    fun mergeWeightsDropsTickerWhenDuplicateRowsNetToZero() {
        val portfolio = PortfolioConfig(
            label = "test",
            tickers = listOf(
                TickerWeight("AAA", 50.0),
                TickerWeight("AAA", -50.0),
                TickerWeight("BBB", 100.0),
            ),
            rebalanceStrategy = RebalanceStrategy.NONE,
            marginStrategies = emptyList(),
        )

        val (tickers, targetWeights) = portfolio.mergeWeights()

        assertEquals(listOf("BBB"), tickers)
        assertEquals(1.0, targetWeights["BBB"])
    }

    @Test
    fun mergeWeightsRejectsNetShortPortfolio() {
        val portfolio = PortfolioConfig(
            label = "test",
            tickers = listOf(
                TickerWeight("AAA", -100.0),
            ),
            rebalanceStrategy = RebalanceStrategy.NONE,
            marginStrategies = emptyList(),
        )

        assertFailsWith<IllegalArgumentException> {
            portfolio.mergeWeights()
        }
    }
}
