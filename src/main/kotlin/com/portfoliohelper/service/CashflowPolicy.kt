package com.portfoliohelper.service

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

internal object CashflowPolicy {
    fun paymentsPerYear(frequency: CashflowFrequency): Int = when (frequency) {
        CashflowFrequency.MONTHLY -> 12
        CashflowFrequency.QUARTERLY -> 4
        CashflowFrequency.YEARLY -> 1
        CashflowFrequency.NONE -> 0
    }

    fun fixedPayment(config: CashflowConfig): Double =
        if (config.mode == CashflowMode.FIXED) config.amount else 0.0

    fun guardrailPayment(config: CashflowConfig, annualWithdrawal: Double): Double {
        val payments = paymentsPerYear(config.frequency)
        return if (config.mode == CashflowMode.GUARDRAIL_WITHDRAWAL && payments > 0) {
            -annualWithdrawal / payments
        } else {
            0.0
        }
    }

    fun reviewedAnnualWithdrawal(
        config: CashflowConfig,
        priorAnnualWithdrawal: Double,
        portfolioValueBeforeWithdrawal: Double,
        completedYearRealInvestmentReturn: Double,
        completedYearInflationRate: Double,
    ): Double {
        config.validate()
        var candidate =
            if (completedYearRealInvestmentReturn > 0.0) {
                priorAnnualWithdrawal * (1.0 + completedYearInflationRate)
            } else {
                priorAnnualWithdrawal
            }
        val rate =
            if (portfolioValueBeforeWithdrawal > 0.0) candidate / portfolioValueBeforeWithdrawal
            else Double.POSITIVE_INFINITY
        candidate = when {
            rate < config.lowerWithdrawalRate!! -> candidate * 1.1
            rate > config.upperWithdrawalRate!! -> candidate * 0.9
            else -> candidate
        }
        return max(candidate, config.minimumAnnualWithdrawal ?: 0.0)
    }

    fun applyToPortfolio(portfolioValue: Double, requestedCashflow: Double): Pair<Double, Double> {
        if (portfolioValue <= 0.0) return 0.0 to 0.0
        val applied = if (requestedCashflow < 0.0) {
            -min(-requestedCashflow, portfolioValue)
        } else {
            requestedCashflow
        }
        return (portfolioValue + applied).coerceAtLeast(0.0) to applied
    }
}

internal class CashflowRuntime(
    private val config: CashflowConfig?,
    private val dates: List<LocalDate>,
    private val inflationFactors: List<Double> = List(dates.size) { 1.0 },
) {
    private var annualWithdrawal = config?.initialAnnualWithdrawal ?: 0.0
    private var policyYear = 0
    private var nominalInvestmentFactor = 1.0
    private var reviewInflationFactor = inflationFactors.firstOrNull() ?: 1.0
    private var depleted = false
    private var currentIndex = 0
    val appliedCashflows: MutableList<Double> = MutableList(dates.size) { 0.0 }

    init {
        config?.validate()
    }

    fun requestedCashflow(index: Int, portfolioValueBeforeWithdrawal: Double, investmentFactor: Double): Double {
        if (config == null || depleted || index <= 0) return 0.0
        currentIndex = index
        nominalInvestmentFactor *= investmentFactor.takeIf { it.isFinite() && it >= 0.0 } ?: 1.0
        val prevDate = dates[index - 1]
        val curDate = dates[index]
        if (config.mode == CashflowMode.FIXED) {
            return if (BacktestService.isCashflowDate(config.frequency, prevDate, curDate)) config.amount else 0.0
        }

        val completedPolicyYears = completedPolicyYears(dates.first(), curDate)
        if (completedPolicyYears > policyYear) {
            val currentInflationFactor = inflationFactors.getOrElse(index) { reviewInflationFactor }
            val inflationRate =
                if (reviewInflationFactor > 0.0) currentInflationFactor / reviewInflationFactor - 1.0 else 0.0
            val realInvestmentReturn =
                if (1.0 + inflationRate > 0.0) nominalInvestmentFactor / (1.0 + inflationRate) - 1.0
                else nominalInvestmentFactor - 1.0
            annualWithdrawal = CashflowPolicy.reviewedAnnualWithdrawal(
                config,
                annualWithdrawal,
                portfolioValueBeforeWithdrawal,
                realInvestmentReturn,
                inflationRate,
            )
            policyYear = completedPolicyYears
            nominalInvestmentFactor = 1.0
            reviewInflationFactor = currentInflationFactor
        }
        return if (BacktestService.isCashflowDate(config.frequency, prevDate, curDate)) {
            CashflowPolicy.guardrailPayment(config, annualWithdrawal)
        } else {
            0.0
        }
    }

    fun apply(portfolioValue: Double, requestedCashflow: Double): Pair<Double, Double> {
        val result = CashflowPolicy.applyToPortfolio(portfolioValue, requestedCashflow)
        appliedCashflows[currentIndex] = result.second
        if (portfolioValue > 0.0 && result.first == 0.0 && requestedCashflow < 0.0) depleted = true
        return result
    }

    private fun completedPolicyYears(start: LocalDate, current: LocalDate): Int {
        var years = current.year - start.year
        if (current < start.plusYears(years.toLong())) years--
        return years.coerceAtLeast(0)
    }
}
