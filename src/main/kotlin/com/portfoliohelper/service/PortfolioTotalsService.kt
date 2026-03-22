package com.portfoliohelper.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class PortfolioTotalsSnapshot(
    val portfolioId: String,
    val stockGrossUsd: Double,
    val stockGrossKnown: Boolean,
    val cashTotalUsd: Double,
    val cashKnown: Boolean,
    val grandTotalUsd: Double,
    val grandTotalKnown: Boolean,
    val marginUsd: Double,
    val dayChangeUsd: Double,
    val prevDayUsd: Double
)

class PortfolioTotalsService(
    private val portfolioId: String,
    private val stockSvc: StockDisplayService,
    private val cashSvc: CashDisplayService
) {
    private val _updates = MutableSharedFlow<PortfolioTotalsSnapshot>(replay = 1, extraBufferCapacity = 8)
    val updates: SharedFlow<PortfolioTotalsSnapshot> = _updates.asSharedFlow()

    private var lastStock: StockDisplaySnapshot? = null
    private var lastCash: CashDisplaySnapshot? = null

    fun initialize(scope: CoroutineScope) {
        scope.launch { stockSvc.updates.collect { synchronized(this@PortfolioTotalsService) { lastStock = it }; tryEmit() } }
        scope.launch { cashSvc.updates.collect  { synchronized(this@PortfolioTotalsService) { lastCash  = it }; tryEmit() } }
    }

    @Synchronized
    private fun tryEmit() {
        val s = lastStock ?: return
        val c = lastCash  ?: return
        _updates.tryEmit(PortfolioTotalsSnapshot(
            portfolioId    = portfolioId,
            stockGrossUsd  = s.stockGrossUsd,
            stockGrossKnown = s.stockGrossKnown,
            cashTotalUsd   = c.totalUsd,
            cashKnown      = c.totalKnown,
            grandTotalUsd  = s.stockGrossUsd + c.totalUsd,
            grandTotalKnown = s.stockGrossKnown && c.totalKnown,
            marginUsd      = c.marginUsd,
            dayChangeUsd   = s.dayChangeUsd,
            prevDayUsd     = s.prevDayUsd
        ))
    }
}
