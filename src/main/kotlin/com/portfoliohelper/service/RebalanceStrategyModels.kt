package com.portfoliohelper.service

import java.time.LocalDate

// ── Enums ─────────────────────────────────────────────────────────────────────

enum class DeviationMode { ABSOLUTE, RELATIVE }

enum class RebalancePeriodOverride { INHERIT, NONE, MONTHLY, QUARTERLY, YEARLY }

enum class CashflowScaling { SCALED_BY_TARGET_MARGIN, SCALED_BY_CURRENT_MARGIN, NO_SCALING }

enum class DipSurgeScope { INDIVIDUAL_STOCK, WHOLE_PORTFOLIO }

enum class Direction { BUY, SELL }

// ── DipSurgeKey ───────────────────────────────────────────────────────────────

sealed class DipSurgeKey {
    object WholePortfolio : DipSurgeKey()
    data class Stock(val ticker: String) : DipSurgeKey()
}

// ── TriggerChecker interface ──────────────────────────────────────────────────

interface TriggerChecker {
    /** Advance internal cached state with today's data. Called every day for all keys. */
    fun advance(dayIndex: Int)

    /** Evaluate trigger against cached state. Called only for eligible keys. */
    fun check(dayIndex: Int, direction: Direction): Boolean
}

// ── DipSurgeExecutor interface ────────────────────────────────────────────────

interface DipSurgeExecutor {
    /**
     * Called every day per key. Handles both ongoing installment runs and new trigger fires.
     * [currentValue] ticker price for Stock keys, total holdings value for WholePortfolio.
     */
    fun advance(
        dayIndex: Int,
        triggered: Boolean,
        currentValue: Double,
        eligible: () -> Double,
        execute: (Double) -> Unit
    )
}

// ── Sealed trigger types ──────────────────────────────────────────────────────

sealed interface PriceMoveTrigger {
    fun buildChecker(
        key: DipSurgeKey,
        dates: List<LocalDate>,
        rawPrices: Map<String, Map<LocalDate, Double>>
    ): TriggerChecker

    data class VsNDaysAgo(val nDays: Int, val pct: Double) : PriceMoveTrigger {
        override fun buildChecker(
            key: DipSurgeKey,
            dates: List<LocalDate>,
            rawPrices: Map<String, Map<LocalDate, Double>>
        ): TriggerChecker =
            VsNDaysAgoChecker(
                nDays,
                pct,
                (key as? DipSurgeKey.Stock)?.let { rawPrices[it.ticker] },
                dates
            )
    }

    data class VsRunningAvg(val nDays: Int, val pct: Double) : PriceMoveTrigger {
        override fun buildChecker(
            key: DipSurgeKey,
            dates: List<LocalDate>,
            rawPrices: Map<String, Map<LocalDate, Double>>
        ): TriggerChecker =
            VsRunningAvgChecker(
                nDays,
                pct,
                (key as? DipSurgeKey.Stock)?.let { rawPrices[it.ticker] },
                dates
            )
    }

    /** Drawdown from peak (buy the dip) or surge from trough (sell on surge) */
    data class PeakDeviation(val pct: Double) : PriceMoveTrigger {
        override fun buildChecker(
            key: DipSurgeKey,
            dates: List<LocalDate>,
            rawPrices: Map<String, Map<LocalDate, Double>>
        ): TriggerChecker =
            PeakDeviationChecker(
                pct,
                (key as? DipSurgeKey.Stock)?.let { rawPrices[it.ticker] },
                dates
            )
    }
}

// ── Execution methods ─────────────────────────────────────────────────────────

sealed class ExecutionMethod {
    abstract fun newExecutor(): DipSurgeExecutor

    object Once : ExecutionMethod() {
        override fun newExecutor() = OnceExecutor()
    }

    data class Consecutive(val days: Int) : ExecutionMethod() {
        override fun newExecutor() = ConsecutiveExecutor(days)
    }

    /** Buy/sell in `portions` equal steps, each additional `additionalPct` move */
    data class Stepped(val portions: Int, val additionalPct: Double) : ExecutionMethod() {
        override fun newExecutor() = SteppedExecutor(portions, additionalPct)
    }
}

// ── Strategy sub-configs ──────────────────────────────────────────────────────

/** deviationPct == null → section disabled */
data class MarginTriggerAction(
    val deviationPct: Double,
    val allocStrategy: MarginRebalanceMode,
)

data class DipSurgeConfig(
    val scope: DipSurgeScope,
    val allocStrategy: MarginRebalanceMode?,  // required when scope = WHOLE_PORTFOLIO
    val triggers: List<PriceMoveTrigger>,
    val method: ExecutionMethod,
    val limit: Double,
)

// ── Top-level strategy config ─────────────────────────────────────────────────

data class RebalStrategyConfig(
    val label: String,
    val marginRatio: Double,
    val marginSpread: Double,
    val rebalancePeriod: RebalancePeriodOverride,
    val cashflowImmediateInvestPct: Double,   // 0.0–1.0; default 1.0
    val cashflowScaling: CashflowScaling,
    val cashflowScalingMargin: Double? = null,
    // Section 2: deviation mode applies to all sections
    val deviationMode: DeviationMode,
    // Sections 3 & 4: deviationPct IS the threshold; null = disabled
    val sellOnHighMargin: MarginTriggerAction?,
    val buyOnLowMargin: MarginTriggerAction?,
    // Sections 5 & 6
    val buyTheDip: DipSurgeConfig?,
    val sellOnSurge: DipSurgeConfig?,
    val comfortZoneLow: Double = 0.0,
    val comfortZoneHigh: Double = 0.0
)

data class RebalanceStrategyRequest(
    val fromDate: String?,
    val toDate: String?,
    val portfolio: PortfolioConfig,
    val cashflow: CashflowConfig?,
    val strategies: List<RebalStrategyConfig>,  // exactly 2
    val startingBalance: Double = 10_000.0
)

// ── TriggerChecker implementations ───────────────────────────────────────────

private class VsNDaysAgoChecker(
    private val nDays: Int,
    private val pct: Double,
    private val history: Map<LocalDate, Double>?,
    private val dates: List<LocalDate>
) : TriggerChecker {
    override fun advance(dayIndex: Int) = Unit

    override fun check(dayIndex: Int, direction: Direction): Boolean {
        if (history == null || dayIndex < nDays) return false
        val cur = history[dates[dayIndex]] ?: return false
        val past = history[dates[dayIndex - nDays]] ?: return false
        if (past <= 0) return false
        val move = (cur - past) / past
        return if (direction == Direction.BUY) move < -pct else move > pct
    }
}

private class VsRunningAvgChecker(
    private val nDays: Int,
    private val pct: Double,
    private val history: Map<LocalDate, Double>?,
    private val dates: List<LocalDate>
) : TriggerChecker {
    private val window = ArrayDeque<Double>()

    // Adds the previous day's price so the window at check time equals [i-nDays..i-1].
    override fun advance(dayIndex: Int) {
        if (history == null || dayIndex == 0) return
        val price = history[dates[dayIndex - 1]] ?: return
        window.addLast(price)
        if (window.size > nDays) window.removeFirst()
    }

    override fun check(dayIndex: Int, direction: Direction): Boolean {
        if (history == null || window.isEmpty()) return false
        val cur = history[dates[dayIndex]] ?: return false
        val avg = window.average()
        if (avg <= 0) return false
        val move = (cur - avg) / avg
        return if (direction == Direction.BUY) move < -pct else move > pct
    }
}

private class PeakDeviationChecker(
    private val pct: Double,
    private val history: Map<LocalDate, Double>?,
    private val dates: List<LocalDate>
) : TriggerChecker {
    private var runningPeak = Double.MIN_VALUE
    private var runningTrough = Double.MAX_VALUE

    // Includes the current day in peak/trough tracking, matching original (0..i) semantics.
    override fun advance(dayIndex: Int) {
        if (history == null) return
        val price = history[dates[dayIndex]] ?: return
        if (price > runningPeak) runningPeak = price
        if (price < runningTrough) runningTrough = price
    }

    override fun check(dayIndex: Int, direction: Direction): Boolean {
        if (history == null) return false
        val cur = history[dates[dayIndex]] ?: return false
        return if (direction == Direction.BUY) {
            runningPeak > 0 && (runningPeak - cur) / runningPeak > pct
        } else {
            runningTrough > 0 && (cur - runningTrough) / runningTrough > pct
        }
    }
}

// ── DipSurgeExecutor implementations ─────────────────────────────────────────

public class OnceExecutor : DipSurgeExecutor {
    override fun advance(
        dayIndex: Int,
        triggered: Boolean,
        currentValue: Double,
        eligible: () -> Double,
        execute: (Double) -> Unit
    ) {
        if (triggered) execute(eligible())
    }
}

class ConsecutiveExecutor(private val totalDays: Int) : DipSurgeExecutor {
    private var daysRemaining = 0

    override fun advance(
        dayIndex: Int,
        triggered: Boolean,
        currentValue: Double,
        eligible: () -> Double,
        execute: (Double) -> Unit
    ) {
        if (triggered && daysRemaining == 0) daysRemaining = totalDays
        if (daysRemaining > 0) {
            val e = eligible()
            if (e > 0) execute(e / daysRemaining)
            daysRemaining--
        }
    }
}

class SteppedExecutor(
    private val totalPortions: Int,
    private val additionalPct: Double
) : DipSurgeExecutor {
    private var basePrice = 0.0
    private var portionsFired = 0

    override fun advance(
        dayIndex: Int,
        triggered: Boolean,
        currentValue: Double,
        eligible: () -> Double,
        execute: (Double) -> Unit
    ) {
        if (!triggered) return
        if (portionsFired == 0) {
            basePrice = currentValue
            portionsFired = 1
            execute(eligible() / totalPortions)
        } else if (portionsFired < totalPortions) {
            val expectedDrop = additionalPct * portionsFired
            val actualDrop = if (basePrice > 0) (basePrice - currentValue) / basePrice else 0.0
            if (actualDrop < expectedDrop) return
            val portionIdx = portionsFired
            portionsFired++
            execute(eligible() / (totalPortions - portionIdx))
            if (portionsFired >= totalPortions) portionsFired = 0
        }
    }
}
