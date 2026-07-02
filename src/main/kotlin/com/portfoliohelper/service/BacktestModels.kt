package com.portfoliohelper.service

import kotlinx.serialization.Serializable

// ── Domain types ──────────────────────────────────────────────────────────────

enum class RebalanceStrategy {
    NONE,
    DAILY,
    WEEKLY,
    BI_WEEKLY,
    MONTHLY,
    BI_MONTHLY,
    QUARTERLY,
    EVERY_4_MONTHS,
    HALF_YEARLY,
    YEARLY
}

enum class MarginRebalanceMode {
    CURRENT_WEIGHT,
    PROPORTIONAL,
    FULL_REBALANCE,
    HYBRID_WATERFALL_FULL_REBALANCE,
    UNDERVALUED_PRIORITY,
    WATERFALL,
    DAILY
}

data class TickerWeight(val ticker: String, val weight: Double)

data class LETFComponent(val ticker: String, val multiplier: Double)

data class LETFDefinition(
    val components: List<LETFComponent>,
    val spread: Double,                                                       // annual fraction, default 0.015
    val rebalanceStrategy: RebalanceStrategy = RebalanceStrategy.QUARTERLY,  // default Q
    val expenseRatio: Double = 0.0,                                          // annual fraction, e.g. 0.02 = 2%
    val volatilityAdjustment: Double = 0.0                                   // relative fraction, e.g. 0.20 = +20%
) {
    val totalMultiplier: Double get() = components.sumOf { it.multiplier }
    val borrowedRatio: Double get() = totalMultiplier - 1.0
}

data class MarginConfig(
    val marginRatio: Double,        // e.g. 0.5 = 50% borrow-to-equity
    val marginSpread: Double,       // annualised fraction e.g. 0.015
    val marginDeviationUpper: Double, // upper breach threshold e.g. 0.05
    val marginDeviationLower: Double, // lower breach threshold e.g. 0.05
    val upperRebalanceMode: String = MarginRebalanceMode.PROPORTIONAL.name,
    val lowerRebalanceMode: String = MarginRebalanceMode.PROPORTIONAL.name
)

data class PortfolioConfig(
    val label: String,
    val tickers: List<TickerWeight>,
    val rebalanceStrategy: RebalanceStrategy,
    val marginStrategies: List<MarginConfig>,  // empty = base curve only
    val rebalanceStrategies: List<RebalStrategyConfig> = emptyList(),
    val includeNoMargin: Boolean = true
)

const val DUMMY_TICKER = "DUMMY"

fun isPlaceholderTicker(ticker: String): Boolean =
    ticker.trim().equals(DUMMY_TICKER, ignoreCase = true)

fun PortfolioConfig.withoutPlaceholderTickers(): PortfolioConfig {
    val filtered = tickers.filterNot { isPlaceholderTicker(it.ticker) }
    return if (filtered.size == tickers.size) this else copy(tickers = filtered)
}

enum class CashflowFrequency { NONE, MONTHLY, QUARTERLY, YEARLY }

data class CashflowConfig(
    val amount: Double,
    val frequency: CashflowFrequency
)

enum class MarketTimingReferenceSource { PORTFOLIO, TICKER }
enum class MarketTimingInterestMode { SPREAD, FIXED }

data class MultiBacktestRequest(
    val fromDate: String?,
    val toDate: String?,
    val portfolios: List<PortfolioConfig>,  // 1–3
    val cashflow: CashflowConfig? = null,
    val startingBalance: Double = 10_000.0,
    val zeroMarginInterest: Boolean = false,
)

data class MarketTimingRequest(
    val fromDate: String?,
    val toDate: String?,
    val portfolio: PortfolioConfig,
    val drawdownConfigs: List<MarketTimingDrawdownConfig>,
    val referenceSource: MarketTimingReferenceSource,
    val referenceTicker: String? = null,
    val interestMode: MarketTimingInterestMode,
    val annualSpread: Double? = null,
    val fixedAnnualRate: Double? = null,
    val startingBalance: Double = 10_000.0
)

data class MarketTimingDrawdownConfig(
    val drawdownPct: Double,
    val zeroWindowMonths: Int = 0,
)

@Serializable
data class DataPoint(val date: String, val value: Double)

@Serializable
data class MarketTimingPoint(
    val date: String,
    val value: Double? = null,
    val basePortfolioReturn: Double? = null,
    val marginExcessReturn: Double? = null,
    val triggerDate: String? = null,
    val daysToTrigger: Int? = null,
    val referenceDrawdown: Double? = null,
    val zeroingWindow: Boolean = false,
    val nonZeroWindowId: Int? = null,
)

@Serializable
data class MarketTimingSummary(
    val totalPoints: Int,
    val triggeredPoints: Int,
    val bestValue: Double? = null,
    val worstValue: Double? = null,
    val averageValue: Double? = null,
    val medianValue: Double? = null,
    val nonZeroAverageValue: Double? = null,
    val nonZeroMedianValue: Double? = null,
    val winRate: Double? = null,
    val averageDaysToTrigger: Double? = null,
)

@Serializable
data class MarketTimingResult(
    val drawdownPct: Double,
    val zeroWindowMonths: Int = 0,
    val points: List<MarketTimingPoint>,
    val summary: MarketTimingSummary,
)

@Serializable
data class MarketTimingMultiResult(
    val referenceLabel: String,
    val referencePoints: List<DataPoint>,
    val results: List<MarketTimingResult>,
)

@Serializable
data class ActionPointDetail(
    val tradingDayIndex: Int? = null,
    val key: String? = null,
    val direction: String? = null,
    val triggerValue: Double? = null,
    val cooldownDays: Int? = null,
    val daysSincePrevious: Int? = null,
    val amount: Double? = null,
    val eligibleAmount: Double? = null,
    val minAdjustment: Double? = null,
    val grossBefore: Double? = null,
    val grossAfter: Double? = null,
    val marginBefore: Double? = null,
    val marginAfter: Double? = null,
    val allocStrategy: String? = null,
)

@Serializable
data class ActionPoint(val date: String, val type: String, val detail: ActionPointDetail? = null)

@Serializable
data class VmTimingPoint(val date: String, val cape: Double, val valueFactor: Double)

@Serializable
data class BacktestStats(
    val cagr: Double,
    val maxDrawdown: Double,
    val sharpe: Double,
    val ulcerIndex: Double,
    val upi: Double,
    val annualVolatility: Double,
    val longestDrawdownDays: Int,
    val endingValue: Double,
    val marginUpperTriggers: Int? = null,   // deviation breach above target (market fell, leverage too high)
    val marginLowerTriggers: Int? = null    // deviation breach below target (market rose, leverage too low)
)

@Serializable
data class CurveResult(
    val label: String,
    val points: List<DataPoint>,
    val stats: BacktestStats,
    val marginPoints: List<DataPoint>? = null,
    val actionPoints: List<ActionPoint>? = null,
    val vmTimingPoints: List<VmTimingPoint>? = null
)

@Serializable
data class PortfolioResult(
    val label: String,
    val curves: List<CurveResult>  // index 0 = no-margin; rest = margin variants
)

@Serializable
data class MultiBacktestResult(
    val portfolios: List<PortfolioResult>,
    val warnings: List<String> = emptyList()
)

/** Merges duplicate tickers by summing weights, then normalises to sum-to-1. */
fun PortfolioConfig.mergeWeights(): Pair<List<String>, Map<String, Double>> {
    val merged = linkedMapOf<String, Double>()
    for (tw in tickers) {
        if (isPlaceholderTicker(tw.ticker)) continue
        merged[tw.ticker] = (merged[tw.ticker] ?: 0.0) + tw.weight
    }
    merged.entries.removeIf { it.value == 0.0 }
    val tickerList = merged.keys.toList()
    val totalWeight = tickerList.sumOf { merged[it] ?: 0.0 }
    if (tickerList.isNotEmpty() && totalWeight <= 0.0) {
        throw IllegalArgumentException("Portfolio net weight must be positive after merging signed rows.")
    }
    var allocated = 0.0
    val targetWeights =
        if (totalWeight > 0.0) {
            tickerList.mapIndexed { index, ticker ->
                val weight =
                    if (index == tickerList.lastIndex) {
                        1.0 - allocated
                    } else {
                        (merged[ticker] ?: 0.0) / totalWeight
                    }
                allocated += weight
                ticker to weight
            }.toMap()
        } else {
            tickerList.associateWith { 0.0 }
        }
    return tickerList to targetWeights
}
