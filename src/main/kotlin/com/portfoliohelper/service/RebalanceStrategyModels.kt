package com.portfoliohelper.service

// ── Enums ─────────────────────────────────────────────────────────────────────

enum class DeviationMode { ABSOLUTE, RELATIVE }

enum class RebalancePeriodOverride { INHERIT, NONE, MONTHLY, QUARTERLY, YEARLY }

enum class CashflowScaling { SCALED_BY_TARGET_MARGIN, SCALED_BY_CURRENT_MARGIN, NO_SCALING }

enum class DipSurgeScope { INDIVIDUAL_STOCK, WHOLE_PORTFOLIO }

// ── Sealed trigger / execution types ─────────────────────────────────────────

sealed class PriceMoveTrigger {
    data class VsNDaysAgo(val nDays: Int, val pct: Double) : PriceMoveTrigger()
    data class VsRunningAvg(val nDays: Int, val pct: Double) : PriceMoveTrigger()
    /** Drawdown from peak (buy the dip) or surge from trough (sell on surge) */
    data class PeakDeviation(val pct: Double) : PriceMoveTrigger()
}

sealed class ExecutionMethod {
    object Once : ExecutionMethod()
    data class Consecutive(val days: Int) : ExecutionMethod()
    /** Buy/sell in `portions` equal steps, each additional `additionalPct` move */
    data class Stepped(val portions: Int, val additionalPct: Double) : ExecutionMethod()
}

// ── Strategy sub-configs ──────────────────────────────────────────────────────

/** deviationPct == null → section disabled */
data class MarginTriggerAction(
    val deviationPct: Double?,
    val allocStrategy: MarginRebalanceMode?
)

data class DipSurgeConfig(
    val scope: DipSurgeScope,
    val allocStrategy: MarginRebalanceMode?,  // required when scope = WHOLE_PORTFOLIO
    val triggers: List<PriceMoveTrigger>,
    val method: ExecutionMethod
)

// ── Top-level strategy config ─────────────────────────────────────────────────

data class RebalStrategyConfig(
    val label: String,
    val marginRatio: Double,
    val marginSpread: Double,
    val rebalancePeriod: RebalancePeriodOverride,
    val cashflowImmediateInvestPct: Double,   // 0.0–1.0; default 1.0
    val cashflowScaling: CashflowScaling,
    // Section 2: deviation mode applies to all sections
    val deviationMode: DeviationMode,
    val upperLimit: Double?,
    val lowerLimit: Double?,
    // Sections 3 & 4: deviationPct IS the threshold; null = disabled
    val sellOnHighMargin: MarginTriggerAction,
    val buyOnLowMargin: MarginTriggerAction,
    // Sections 5 & 6
    val buyTheDip: DipSurgeConfig?,
    val sellOnSurge: DipSurgeConfig?
)

data class RebalanceStrategyRequest(
    val fromDate: String?,
    val toDate: String?,
    val portfolio: PortfolioConfig,
    val cashflow: CashflowConfig?,
    val strategies: List<RebalStrategyConfig>  // exactly 2
)
