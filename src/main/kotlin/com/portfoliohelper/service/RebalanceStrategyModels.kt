package com.portfoliohelper.service

// ── Enums ─────────────────────────────────────────────────────────────────────

enum class DeviationMode { ABSOLUTE, RELATIVE }

enum class RebalancePeriodOverride {
    INHERIT,
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

enum class CashflowScaling { SCALED_BY_TARGET_MARGIN, SCALED_BY_CURRENT_MARGIN, NO_SCALING }

enum class DipSurgeScope { INDIVIDUAL_STOCK, BASE_PORTFOLIO }

enum class PortfolioTriggerSource { STRATEGY_GROSS, STRATEGY_VALUE, REFERENCE_PORTFOLIO }

enum class Direction { BUY, SELL }

enum class MarginRebalanceTradeDirection { BOTH, BUY_ONLY, SELL_ONLY }

enum class CapeSource { US, WORLD }

enum class DerivedTargetScaleFunction {
    SIGMOID,
    ADAPTIVE_LOW_SIGMOID,
    LINEAR,
    STEP,
    HYSTERESIS_STEP,
    HYSTERESIS_STAIRS,
    HYSTERESIS_STAIRS_MOMENTUM,
    HYSTERESIS_STAIRS_REF_BL_RESET,
}

enum class HysteresisStairsReferenceMode { RESET_REF, BUY_LOW_INTENTION }

enum class HysteresisStairsFallMode { DIRECT, MOMENTUM }

// ── DipSurgeKey ───────────────────────────────────────────────────────────────

sealed class DipSurgeKey {
    data class Portfolio(val source: PortfolioTriggerSource, val referenceTicker: String? = null) : DipSurgeKey()
    data class Stock(val ticker: String) : DipSurgeKey()
}

// ── TriggerChecker interface ──────────────────────────────────────────────────

interface TriggerChecker {
    /** Called every day with today's value (stock price for Stock keys, selected portfolio trigger value for Portfolio keys). */
    fun advance(value: Double)

    /** Evaluate trigger against accumulated state. Called after advance on the same day. */
    fun check(direction: Direction): Boolean
}

// ── DipSurgeExecutor interface ────────────────────────────────────────────────

interface DipSurgeExecutor {
    /**
     * Called every day per key. Handles both ongoing installment runs and new trigger fires.
     * [currentValue] ticker price for Stock keys, selected portfolio trigger value for Portfolio keys.
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
    val allocStrategy: String,
    val targetMargin: Double,
)

data class DipSurgeConfig(
    val scope: DipSurgeScope,
    val allocStrategy: String?,  // required when scope = BASE_PORTFOLIO
    val portfolioSource: PortfolioTriggerSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
    val referenceTicker: String? = null,       // optional 100% reference ticker when portfolioSource = REFERENCE_PORTFOLIO
    val triggers: List<PriceMoveTrigger>,
    val method: ExecutionMethod,
    val limit: Double,
    val coolingOffDays: Int = 10,
    val minAdjustmentPct: Double = 0.005,
)

data class DrawdownMarginOverrideConfig(
    val enabled: Boolean = false,
    val portfolioSource: PortfolioTriggerSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
    val referenceTicker: String? = null,
    val enterDrawdownPct: Double = 0.10,
    val exitDrawdownPct: Double = 0.05,
    val targetMargin: Double = 0.95,
    val rebalancePeriod: RebalancePeriodOverride = RebalancePeriodOverride.BI_MONTHLY,
    val rebalanceOnEnter: Boolean = true,
    val allocStrategy: String = MarginRebalanceMode.PROPORTIONAL.name,
    val buyAllocStrategy: String = MarginRebalanceMode.PROPORTIONAL.name,
    val sellAllocStrategy: String = MarginRebalanceMode.PROPORTIONAL.name,
    val tradeDirection: MarginRebalanceTradeDirection = MarginRebalanceTradeDirection.BOTH,
)

data class DrawdownMarginTriggerTier(
    val enterDrawdownPct: Double = 0.10,
    val exitDrawdownPct: Double = 0.05,
    val triggerMargin: Double = 0.0,
    val allocStrategy: String = MarginRebalanceMode.PROPORTIONAL.name,
    val targetMargin: Double = 0.5,
)

data class DrawdownMarginTriggerAction(
    val portfolioSource: PortfolioTriggerSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
    val referenceTicker: String? = null,
    val momentumLookbackMonths: Int? = null,
    val exitExtensionMonths: Int = 0,
    val exitTargetMargin: Double? = null,
    val enterDrawdownPct: Double = 0.10,
    val exitDrawdownPct: Double = 0.05,
    val triggerMargin: Double = 0.0,
    val allocStrategy: String = MarginRebalanceMode.PROPORTIONAL.name,
    val targetMargin: Double = 0.5,
    val tiers: List<DrawdownMarginTriggerTier> = emptyList(),
) {
    fun effectiveTiers(): List<DrawdownMarginTriggerTier> =
        tiers.takeIf { it.isNotEmpty() }
            ?: listOf(
                DrawdownMarginTriggerTier(
                    enterDrawdownPct = enterDrawdownPct,
                    exitDrawdownPct = exitDrawdownPct,
                    triggerMargin = triggerMargin,
                    allocStrategy = allocStrategy,
                    targetMargin = targetMargin,
                )
            )
}

// ── Top-level strategy config ─────────────────────────────────────────────────

data class VmTimingMrConfig(
    val enabled: Boolean = false,
    val capeSource: CapeSource = CapeSource.WORLD,
    val lowerMargin: Double = -0.50,
    val upperMargin: Double = 0.50,
    val momentumSource: PortfolioTriggerSource = PortfolioTriggerSource.REFERENCE_PORTFOLIO,
    val momentumReferenceTicker: String? = null,
    val momentumLookbackMonths: Int = 12,
    val rebalancePeriod: RebalancePeriodOverride = RebalancePeriodOverride.MONTHLY,
    val allocStrategy: String = MarginRebalanceMode.PROPORTIONAL.name,
)

data class DerivedTargetStepConfig(
    val referenceMargin: Double = 0.60,
    val targetMargin: Double = 0.50,
)

data class DerivedTargetScaleConfig(
    val function: DerivedTargetScaleFunction = DerivedTargetScaleFunction.SIGMOID,
    val referenceLower: Double = 0.50,
    val referenceUpper: Double = 1.00,
    val targetLower: Double = 0.30,
    val targetUpper: Double = 1.00,
    val sigmoidSteepness: Double = 8.0,
    val stepBaseTarget: Double = 0.50,
    val momentumLookbackMonths: Int = 12,
    val hysteresisStairsReferenceMode: HysteresisStairsReferenceMode = HysteresisStairsReferenceMode.RESET_REF,
    val hysteresisStairsFallMode: HysteresisStairsFallMode = HysteresisStairsFallMode.DIRECT,
    val steps: List<DerivedTargetStepConfig> = listOf(DerivedTargetStepConfig()),
)

enum class DerivedMarginReferenceSource { BASE_STRATEGY, STANDALONE_TICKER }

enum class DerivedMarginReferenceMetric { MARGIN, EQUITY_CUSHION, MARGIN_COVERAGE }

data class DerivedSubStrategyConfig(
    val label: String,
    val enabled: Boolean = true,
    val marginReferenceSource: DerivedMarginReferenceSource = DerivedMarginReferenceSource.BASE_STRATEGY,
    val marginReferenceTicker: String? = null,
    val marginReferenceMetric: DerivedMarginReferenceMetric = DerivedMarginReferenceMetric.MARGIN,
    val scale: DerivedTargetScaleConfig = DerivedTargetScaleConfig(),
    val absoluteDeviationPct: Double = 0.05,
    val buyDeviationPct: Double = absoluteDeviationPct,
    val sellDeviationPct: Double = absoluteDeviationPct,
    val timeoutDays: Int = 10,
    val maxMargin: Double = 1.0,
    val allocStrategy: String = MarginRebalanceMode.PROPORTIONAL.name,
    val buyAllocStrategy: String = allocStrategy,
    val sellAllocStrategy: String = allocStrategy,
)

data class RebalStrategyConfig(
    val label: String,
    val marginRatio: Double,
    val marginSpread: Double,
    val portfolioRebalancePeriod: RebalancePeriodOverride = RebalancePeriodOverride.INHERIT,
    val portfolioRebalanceUseComfortZone: Boolean = true,
    val marginRebalanceEnabled: Boolean = true,
    val rebalancePeriod: RebalancePeriodOverride,
    val rebalanceAllocStrategy: String = MarginRebalanceMode.PROPORTIONAL.name,
    val marginRebalanceTradeDirection: MarginRebalanceTradeDirection = MarginRebalanceTradeDirection.BOTH,
    val marginRebalanceRestoreMargin: Double? = null,
    val drawdownMarginOverride: DrawdownMarginOverrideConfig? = null,
    val cashflowImmediateInvestPct: Double,   // 0.0–1.0; default 1.0
    val cashflowScaling: CashflowScaling,
    val cashflowScalingMargin: Double? = null,
    // Section 2: deviation mode applies to all sections
    val deviationMode: DeviationMode,
    // Sections 3 & 4: deviationPct IS the threshold; null = disabled
    val sellOnHighMargin: MarginTriggerAction?,
    val buyOnLowMargin: MarginTriggerAction?,
    val drawdownSellOnHighMargin: DrawdownMarginTriggerAction? = null,
    val drawdownBuyOnLowMargin: DrawdownMarginTriggerAction? = null,
    val vmTimingMr: VmTimingMrConfig? = null,
    // Sections 5 & 6
    val buyTheDip: DipSurgeConfig?,
    val sellOnSurge: DipSurgeConfig?,
    val buyTheDipConfigs: List<DipSurgeConfig> = emptyList(),
    val sellOnSurgeConfigs: List<DipSurgeConfig> = emptyList(),
    val useComfortZone: Boolean = true,
    val comfortZoneLow: Double = 0.0,
    val comfortZoneHigh: Double = 0.0,
    val buyCooldownAfterSellHighDays: Int = 10,
    val sellCooldownAfterBuyLowDays: Int = 10,
    val derivedSubStrategies: List<DerivedSubStrategyConfig> = emptyList(),
)

data class RebalanceStrategyRequest(
    val fromDate: String?,
    val toDate: String?,
    val portfolio: PortfolioConfig,
    val cashflow: CashflowConfig?,
    val strategies: List<RebalStrategyConfig>,  // exactly 2
    val startingBalance: Double = 10_000.0,
    val includeActionDiagnostics: Boolean = false,
    val zeroMarginInterest: Boolean = false,
)

enum class RebalanceOptimizationMetric { CAGR, SHARPE, UPI }

enum class BlockedCrossValidationScoreMode { TRAINING, VALIDATION }

data class BlockedCrossValidationConfig(
    val blocks: Int,
    val validationBlock: Int,
    val mode: BlockedCrossValidationScoreMode,
)

data class RebalanceStrategyScoreBatchRequest(
    val fromDate: String?,
    val toDate: String?,
    val portfolios: List<PortfolioConfig>,
    val cashflow: CashflowConfig?,
    val strategies: List<RebalStrategyConfig>,
    val portfolioRebalanceStrategies: List<RebalanceStrategy> = emptyList(),
    val startingBalance: Double = 10_000.0,
    val metric: RebalanceOptimizationMetric = RebalanceOptimizationMetric.CAGR,
    val blockedCrossValidation: BlockedCrossValidationConfig? = null,
)

// ── TriggerChecker implementations ───────────────────────────────────────────

private class VsNDaysAgoChecker(
    nDays: Int,
    private val pct: Double
) : TriggerChecker {
    private val nDays = nDays.coerceAtLeast(1)
    private val window = ArrayDeque<Double>()

    override fun advance(value: Double) {
        window.addLast(value)
        if (window.size > nDays) window.removeFirst()
    }

    override fun check(direction: Direction): Boolean {
        if (window.size < nDays) return false
        val cur = window.last()
        return if (direction == Direction.BUY) {
            val high = window.maxOrNull() ?: return false
            high > 0 && (high - cur) / high > pct
        } else {
            val low = window.minOrNull() ?: return false
            low > 0 && (cur - low) / low > pct
        }
    }
}

private class VsRunningAvgChecker(
    nDays: Int,
    private val pct: Double
) : TriggerChecker {
    private val nDays = nDays.coerceAtLeast(1)
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
        if (window.size < nDays || current.isNaN()) return false
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

class OnceExecutor : DipSurgeExecutor {
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
