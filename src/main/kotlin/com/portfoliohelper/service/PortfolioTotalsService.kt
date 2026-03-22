package com.portfoliohelper.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    stockSvc: StockDisplayService,
    cashSvc: CashDisplayService,
    scope: CoroutineScope
) {
    val updates: StateFlow<PortfolioTotalsSnapshot?> = combine(stockSvc.updates, cashSvc.updates) { s, c ->
        PortfolioTotalsSnapshot(
            portfolioId     = portfolioId,
            stockGrossUsd   = s.stockGrossUsd,
            stockGrossKnown = s.stockGrossKnown,
            cashTotalUsd    = c.totalUsd,
            cashKnown       = c.totalKnown,
            grandTotalUsd   = s.stockGrossUsd + c.totalUsd,
            grandTotalKnown = s.stockGrossKnown && c.totalKnown,
            marginUsd       = c.marginUsd,
            dayChangeUsd    = s.dayChangeUsd,
            prevDayUsd      = s.prevDayUsd
        )
    }.stateIn(scope, SharingStarted.Eagerly, null)
}
