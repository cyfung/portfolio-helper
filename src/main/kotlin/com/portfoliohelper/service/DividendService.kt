package com.portfoliohelper.service

import com.portfoliohelper.AppConfig
import com.portfoliohelper.service.yahoo.YahooHistoricalFetcher
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

object DividendService {
    private lateinit var appScope: CoroutineScope
    private val pendingJobs = ConcurrentHashMap<Int, Job>()
    private val logger = LoggerFactory.getLogger(DividendService::class.java)

    fun initialize(scope: CoroutineScope) {
        appScope = scope
        ManagedPortfolio.getAll().forEach { maybeScheduleCalculation(it) }
        scheduleNextDailyRun()
    }

    /** Called on page load and on startup for each portfolio. No-ops if already up-to-date or job running. */
    fun maybeScheduleCalculation(portfolio: ManagedPortfolio) {
        if (portfolio.getConfig("virtualBalance") != "true") return
        val startDateStr = portfolio.getConfig("dividendStartDate") ?: return
        val startDate = runCatching { LocalDate.parse(startDateStr) }.getOrNull() ?: return
        val endDate = LocalDate.now().minusDays(AppConfig.dividendSafeLagDays)

        // startDate is exclusive — dividends with ex-date > startDate are counted
        if (startDate.plusDays(1) > endDate) return  // nothing in the safe window yet

        if (pendingJobs.containsKey(portfolio.serialId)) return  // already running

        val existingTotal = portfolio.getConfig("dividendTotal")?.toDoubleOrNull()
        val calcUpTo = portfolio.getConfig("dividendCalcUpToDate")
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        val fromDate: LocalDate
        val baseTotal: Double

        if (existingTotal != null && calcUpTo != null) {
            if (calcUpTo >= endDate) return  // already current within the safe window
            fromDate = calcUpTo.plusDays(1)
            baseTotal = existingTotal
        } else {
            fromDate = startDate.plusDays(1)  // start date exclusive
            baseTotal = 0.0
        }

        launchCalculation(portfolio, fromDate, endDate, baseTotal)
    }

    /** Clear dividend data and cancel any in-flight job (call on stock qty change or start date change). */
    fun invalidate(portfolio: ManagedPortfolio) {
        pendingJobs[portfolio.serialId]?.cancel()
        pendingJobs.remove(portfolio.serialId)
        portfolio.saveConfig("dividendTotal", "")
        portfolio.saveConfig("dividendCalcUpToDate", "")
        // Re-schedule so the new calculation starts promptly
        maybeScheduleCalculation(portfolio)
    }

    private fun launchCalculation(
        portfolio: ManagedPortfolio,
        fromDate: LocalDate,
        toDate: LocalDate,
        baseTotal: Double
    ) {
        val job = appScope.launch(Dispatchers.IO) {
            try {
                val stocks = portfolio.getStocks().filter { it.amount > 0 }
                var total = baseTotal
                for (stock in stocks) {
                    ensureActive()
                    val divs = YahooHistoricalFetcher.fetchDividends(stock.label, fromDate, toDate)
                    if (divs.isEmpty()) continue
                    val currency = YahooMarketDataService.getQuote(stock.label)?.currency ?: "USD"
                    val fxRate = if (currency == "USD") 1.0
                    else YahooMarketDataService.getQuote("${currency}USD=X")?.regularMarketPrice ?: 1.0
                    for ((_, amountPerShare) in divs) {
                        total += amountPerShare * stock.amount * fxRate
                    }
                }
                // Atomic write: only save when full run succeeds
                portfolio.saveConfig("dividendTotal", total.toString())
                portfolio.saveConfig("dividendCalcUpToDate", toDate.toString())
                logger.info("Dividend calc done for '${portfolio.slug}': \$${"%.2f".format(total)} up to $toDate")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Dividend calc failed for '${portfolio.slug}'", e)
            } finally {
                pendingJobs.remove(portfolio.serialId)
            }
        }
        pendingJobs[portfolio.serialId] = job
    }

    private fun scheduleNextDailyRun() {
        val now = LocalDateTime.now()
        val next1am = now.toLocalDate().plusDays(1).atTime(1, 0)
        val delayMs = ChronoUnit.MILLIS.between(now, next1am)
        appScope.launch {
            delay(delayMs)
            ManagedPortfolio.getAll().forEach { maybeScheduleCalculation(it) }
            scheduleNextDailyRun()
        }
    }
}
