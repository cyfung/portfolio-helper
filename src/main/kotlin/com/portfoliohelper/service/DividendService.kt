package com.portfoliohelper.service

import com.portfoliohelper.AppConfig
import com.portfoliohelper.model.Stock
import com.portfoliohelper.service.yahoo.YahooHistoricalFetcher
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class DividendSnapshot(val portfolioId: String, val total: Double, val calcUpToDate: String)

class DividendService(
    private val portfolio: ManagedPortfolio,
    stocks: StateFlow<List<Stock>>,
    configFlow: StateFlow<Map<String, String>>,
    scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(DividendService::class.java)

    private data class DividendParams(val virtualBalance: Boolean, val startDate: LocalDate?)

    private val params: StateFlow<DividendParams> = configFlow
        .map { cfg ->
            DividendParams(
                virtualBalance = cfg["virtualBalance"] == "true",
                startDate = cfg["dividendStartDate"]
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, DividendParams(false, null))

    // Advances daily to extend the safe calculation window
    private val currentDate = MutableStateFlow(LocalDate.now())

    private val _state = MutableStateFlow<DividendSnapshot?>(null)
    val updates: StateFlow<DividendSnapshot?> = _state.asStateFlow()

    private var calculationJob: Job? = null

    init {
        scope.launch {
            combine(stocks, params, currentDate) { s, p, _ -> Pair(s, p) }
                .collect { (currentStocks, currentParams) ->
                    calculationJob?.cancel()
                    if (!currentParams.virtualBalance || currentParams.startDate == null) {
                        _state.value = null
                        return@collect
                    }
                    val endDate = LocalDate.now().minusDays(AppConfig.dividendSafeLagDays)
                    if (currentParams.startDate.plusDays(1) > endDate) {
                        _state.value = DividendSnapshot(portfolio.slug, 0.0, "")
                        return@collect
                    }
                    _state.value = DividendSnapshot(portfolio.slug, 0.0, "") // loading
                    val startDate = currentParams.startDate
                    calculationJob = scope.launch(Dispatchers.IO) {
                        runCalculation(currentStocks, startDate, endDate)
                    }
                }
        }
        scope.launch { dailyScheduler() }
    }

    private suspend fun runCalculation(stocks: List<Stock>, startDate: LocalDate, endDate: LocalDate) {
        try {
            val activeStocks = stocks.filter { it.amount > 0 }
            var total = 0.0
            for (stock in activeStocks) {
                currentCoroutineContext().ensureActive()
                val divs = YahooHistoricalFetcher.fetchDividends(stock.label, startDate.plusDays(1), endDate)
                if (divs.isEmpty()) continue
                val currency = YahooMarketDataService.getQuote(stock.label)?.currency ?: "USD"
                val fxRate = if (currency == "USD") 1.0
                else YahooMarketDataService.getQuote("${currency}USD=X")?.regularMarketPrice ?: 1.0
                for ((_, amountPerShare) in divs) {
                    total += amountPerShare * stock.amount * fxRate
                }
            }
            portfolio.saveConfig("dividendTotal", total.toString())
            portfolio.saveConfig("dividendCalcUpToDate", endDate.toString())
            logger.info("Dividend calc done for '${portfolio.slug}': ${"%.2f".format(total)} up to $endDate")
            _state.value = DividendSnapshot(portfolio.slug, total, endDate.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Dividend calc failed for '${portfolio.slug}'", e)
            _state.value = null
        }
    }

    private suspend fun dailyScheduler() {
        val now = LocalDateTime.now()
        val next1am = now.toLocalDate().plusDays(1).atTime(1, 0)
        delay(ChronoUnit.MILLIS.between(now, next1am))
        currentDate.value = LocalDate.now()
        dailyScheduler()
    }
}
