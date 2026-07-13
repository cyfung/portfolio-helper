package com.portfoliohelper.service

import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal fun tradeMovedGrossInDirection(before: Double, after: Double, direction: Direction): Boolean {
  val epsilon = 1e-9
  return if (direction == Direction.BUY) after > before + epsilon else after < before - epsilon
}

internal fun DipSurgeKey.diagnosticLabel(): String =
    when (this) {
      is DipSurgeKey.Stock -> "STOCK:$ticker"
      is DipSurgeKey.Portfolio -> "PORTFOLIO:${source.name}${referenceTicker?.let { ":$it" } ?: ""}"
    }

internal fun RebalancePeriodOverride.toMarginRebalanceStrategy(): RebalanceStrategy =
    when (this) {
      RebalancePeriodOverride.INHERIT -> RebalanceStrategy.NONE
      RebalancePeriodOverride.NONE -> RebalanceStrategy.NONE
      RebalancePeriodOverride.DAILY -> RebalanceStrategy.DAILY
      RebalancePeriodOverride.WEEKLY -> RebalanceStrategy.WEEKLY
      RebalancePeriodOverride.BI_WEEKLY -> RebalanceStrategy.BI_WEEKLY
      RebalancePeriodOverride.MONTHLY -> RebalanceStrategy.MONTHLY
      RebalancePeriodOverride.BI_MONTHLY -> RebalanceStrategy.BI_MONTHLY
      RebalancePeriodOverride.QUARTERLY -> RebalanceStrategy.QUARTERLY
      RebalancePeriodOverride.EVERY_4_MONTHS -> RebalanceStrategy.EVERY_4_MONTHS
      RebalancePeriodOverride.HALF_YEARLY -> RebalanceStrategy.HALF_YEARLY
      RebalancePeriodOverride.YEARLY -> RebalanceStrategy.YEARLY
    }

internal fun RebalancePeriodOverride.toPortfolioRebalanceStrategy(base: RebalanceStrategy): RebalanceStrategy =
    when (this) {
      RebalancePeriodOverride.INHERIT -> base
      RebalancePeriodOverride.NONE -> RebalanceStrategy.NONE
      RebalancePeriodOverride.DAILY -> RebalanceStrategy.DAILY
      RebalancePeriodOverride.WEEKLY -> RebalanceStrategy.WEEKLY
      RebalancePeriodOverride.BI_WEEKLY -> RebalanceStrategy.BI_WEEKLY
      RebalancePeriodOverride.MONTHLY -> RebalanceStrategy.MONTHLY
      RebalancePeriodOverride.BI_MONTHLY -> RebalanceStrategy.BI_MONTHLY
      RebalancePeriodOverride.QUARTERLY -> RebalanceStrategy.QUARTERLY
      RebalancePeriodOverride.EVERY_4_MONTHS -> RebalanceStrategy.EVERY_4_MONTHS
      RebalancePeriodOverride.HALF_YEARLY -> RebalanceStrategy.HALF_YEARLY
      RebalancePeriodOverride.YEARLY -> RebalanceStrategy.YEARLY
    }

internal fun RebalStrategyConfig.portfolioWithRebalanceOverride(portfolio: PortfolioConfig): PortfolioConfig {
  val effective = portfolioRebalancePeriod.toPortfolioRebalanceStrategy(portfolio.rebalanceStrategy)
  return if (effective == portfolio.rebalanceStrategy) portfolio else portfolio.copy(rebalanceStrategy = effective)
}

internal fun biWeeklyBucket(date: LocalDate): Long =
    ChronoUnit.WEEKS.between(LocalDate.of(1970, 1, 5), date) / 2

internal fun monthBucket(date: LocalDate, monthsPerBucket: Int): Int =
    date.year * 12 + (date.monthValue - 1) / monthsPerBucket

// Pre-loop resources

internal data class DipSurgeResources(
    val checkersByKey: Map<DipSurgeKey, List<TriggerChecker>>,
    val executorsByKey: Map<DipSurgeKey, DipSurgeExecutor>,
    val allocStrategy: String?,
    val limit: Double,
    val cooldown: DipSurgeCooldown,
    val minAdjustmentPct: Double,
)

internal class DipSurgeCooldown(coolingOffDays: Int = 10) {
  val coolingOffDays = coolingOffDays.coerceAtLeast(0)
  private val lastTriggerIndexByKey = mutableMapOf<DipSurgeKey, Int>()

  fun shouldFire(key: DipSurgeKey, curIndex: Int, rawTriggered: Boolean): Boolean {
    if (!rawTriggered) return false
    val lastTriggerIndex = lastTriggerIndexByKey[key]
      return !(lastTriggerIndex != null && curIndex - lastTriggerIndex <= coolingOffDays)
  }

  fun recordFire(key: DipSurgeKey, curIndex: Int) {
    lastTriggerIndexByKey[key] = curIndex
  }

  fun daysSinceLastFire(key: DipSurgeKey, curIndex: Int): Int? =
      lastTriggerIndexByKey[key]?.let { curIndex - it }
}

internal class ReverseDirectionCooldown(
    buyCooldownAfterSellHighDays: Int,
    sellCooldownAfterBuyLowDays: Int,
) {
  private val buyDays = buyCooldownAfterSellHighDays.coerceAtLeast(0)
  private val sellDays = sellCooldownAfterBuyLowDays.coerceAtLeast(0)
  private var lastSellHighIndex: Int? = null
  private var lastBuyLowIndex: Int? = null

  fun recordSellHigh(curIndex: Int) {
    lastSellHighIndex = curIndex
  }

  fun recordBuyLow(curIndex: Int) {
    lastBuyLowIndex = curIndex
  }

  fun isBlocked(direction: Direction, curIndex: Int): Boolean =
      when (direction) {
        Direction.BUY -> buyDays > 0 && lastSellHighIndex?.let { curIndex - it <= buyDays } == true
        Direction.SELL -> sellDays > 0 && lastBuyLowIndex?.let { curIndex - it <= sellDays } == true
      }

  fun nextAllowedIndex(direction: Direction, curIndex: Int): Int? =
      when (direction) {
        Direction.BUY ->
            lastSellHighIndex
                ?.takeIf { buyDays > 0 && curIndex - it <= buyDays }
                ?.let { it + buyDays + 1 }
        Direction.SELL ->
            lastBuyLowIndex
                ?.takeIf { sellDays > 0 && curIndex - it <= sellDays }
                ?.let { it + sellDays + 1 }
      }
}

internal fun PaperTradingPortfolio.applyTradeDelta(ticker: String, amount: Double) {
  if (amount > 0.0) buy(ticker, amount)
  else if (amount < 0.0) sell(ticker, -amount)
}

internal fun PaperTradingPortfolio.netMarginRatio(): Double {
  val eq = equity()
  return if (eq > 0.0) -cashBalance() / eq else 0.0
}
