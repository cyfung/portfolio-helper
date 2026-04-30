package com.portfoliohelper.service

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
    /** Called every day with today's value (stock price for Stock keys, portfolio gross value for WholePortfolio). */
    fun advance(value: Double)

    /** Evaluate trigger against accumulated state. Called after advance on the same day. */
    fun check(direction: Direction): Boolean
}

// ── DipSurgeExecutor interface ────────────────────────────────────────────────

interface DipSurgeExecutor {
    /**
     * Called every day per key. Handles both ongoing installment runs and new trigger fires.
     * [currentValue] ticker price for Stock keys, total holdings value for WholePortfolio.
     */
    fun advance(
        triggered: Boolean,
        currentValue: Double,
        eligible: () -> Double,
        execute: (Double) -> Unit
    )
}

// ── Sealed trigger types ──────────────────────────────────────────────────────

sealed interface PriceMoveTrigger {
    fun buildChecker(key: DipSurgeKey): TriggerChecker

    data class VsNDaysAgo(val nDays: Int, val pct: Double) : PriceMoveTrigger {
        override fun buildChecker(key: DipSurgeKey): TriggerChecker = VsNDaysAgoChecker(nDays, pct)
    }

    data class VsRunningAvg(val nDays: Int, val pct: Double) : PriceMoveTrigger {
        override fun buildChecker(key: DipSurgeKey): TriggerChecker = VsRunningAvgChecker(nDays, pct)
    }

    /** Drawdown from peak (buy the dip) or surge from trough (sell on surge) */
    data class PeakDeviation(val pct: Double) : PriceMoveTrigger {
        override fun buildChecker(key: DipSurgeKey): TriggerChecker = PeakDeviationChecker(pct)
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
    val targetMargin: Double,
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
    private val pct: Double
) : TriggerChecker {
    private val window = ArrayDeque<Double>()

    override fun advance(value: Double) {
        window.addLast(value)
        if (window.size > nDays + 1) window.removeFirst()
    }

    override fun check(direction: Direction): Boolean {
        if (window.size < nDays + 1) return false
        val past = window.first()
        val cur = window.last()
        if (past <= 0) return false
        val move = (cur - past) / past
        return if (direction == Direction.BUY) move < -pct else move > pct
    }
}

private class VsRunningAvgChecker(
    private val nDays: Int,
    private val pct: Double
) : TriggerChecker {
    private val window = ArrayDeque<Double>()
    private var current = Double.NaN

    // Pushes the previous day's value into the window so the average at check time equals [i-nDays..i-1].
    override fun advance(value: Double) {
        if (!current.isNaN()) {
            window.addLast(current)
            if (window.size > nDays) window.removeFirst()
        }
        current = value
    }

    override fun check(direction: Direction): Boolean {
        if (window.isEmpty() || current.isNaN()) return false
        val avg = window.average()
        if (avg <= 0) return false
        val move = (current - avg) / avg
        return if (direction == Direction.BUY) move < -pct else move > pct
    }
}

private class PeakDeviationChecker(
    private val pct: Double
) : TriggerChecker {
    private var runningPeak = Double.MIN_VALUE
    private var runningTrough = Double.MAX_VALUE
    private var current = Double.NaN

    override fun advance(value: Double) {
        current = value
        if (value > runningPeak) runningPeak = value
        if (value < runningTrough) runningTrough = value
    }

    override fun check(direction: Direction): Boolean {
        if (current.isNaN()) return false
        return if (direction == Direction.BUY) {
            runningPeak > 0 && (runningPeak - current) / runningPeak > pct
        } else {
            runningTrough > 0 && (current - runningTrough) / runningTrough > pct
        }
    }
}

// ── DipSurgeExecutor implementations ─────────────────────────────────────────

public class OnceExecutor : DipSurgeExecutor {
    override fun advance(
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

// ── PaperTradingPortfolio ─────────────────────────────────────────────────────

class PaperTradingPortfolio(
    tickers: List<String>,
    targetWeights: Map<String, Double>,
    startEquity: Double,
    marginRatio: Double
) {
    private val _holdings: MutableMap<String, Double> =
        tickers.associateWith { startEquity * (1.0 + marginRatio) * (targetWeights[it] ?: 0.0) }.toMutableMap()
    private var _cashBalance: Double = -startEquity * marginRatio

    fun grossStockValue(): Double = _holdings.values.sum()
    fun cashBalance(): Double = _cashBalance
    fun equity(): Double = grossStockValue() + _cashBalance
    fun holding(ticker: String): Double = _holdings[ticker] ?: 0.0
    fun currentMarginRatio(): Double {
        val eq = equity()
        return if (eq > 0) (-_cashBalance).coerceAtLeast(0.0) / eq else 0.0
    }

    fun applyDayReturns(returnRatios: Map<String, DoubleArray>, dayIndex: Int) {
        for ((ticker, ratios) in returnRatios)
            _holdings[ticker] = (_holdings[ticker] ?: 0.0) * ratios[dayIndex]
    }

    fun accrueMarginInterest(dailyRate: Double) {
        if (_cashBalance < 0) _cashBalance *= (1.0 + dailyRate)
    }

    /** Buy [amount] of [ticker]: holdings increase, cash decreases. */
    fun buy(ticker: String, amount: Double) {
        _holdings[ticker] = (_holdings[ticker] ?: 0.0) + amount
        _cashBalance -= amount
    }

    /** Sell [amount] of [ticker]: holdings decrease, cash increases. */
    fun sell(ticker: String, amount: Double) {
        _holdings[ticker] = (_holdings[ticker] ?: 0.0) - amount
        _cashBalance += amount
    }

    /** Deposit cash only — no holdings change. */
    fun deposit(amount: Double) {
        _cashBalance += amount
    }
}
