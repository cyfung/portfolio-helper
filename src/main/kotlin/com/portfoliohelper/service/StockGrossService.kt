package com.portfoliohelper.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

@Serializable
data class StockGrossSnapshot(
    val portfolioId: String,
    val stockGrossUsd: Double,
    val stockGrossKnown: Boolean
)

class StockGrossService(stockSvc: StockDisplayService, scope: CoroutineScope) {
    val updates: StateFlow<StockGrossSnapshot?> = stockSvc.updates
        .map { snap -> StockGrossSnapshot(snap.portfolioId, snap.stockGrossUsd, snap.stockGrossKnown) }
        .stateIn(scope, SharingStarted.Eagerly, null)
}
