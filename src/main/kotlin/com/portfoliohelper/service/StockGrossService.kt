package com.portfoliohelper.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class StockGrossSnapshot(
    val portfolioId: String,
    val stockGrossUsd: Double,
    val stockGrossKnown: Boolean
)

class StockGrossService(private val stockSvc: StockDisplayService) {
    private val _updates = MutableSharedFlow<StockGrossSnapshot>(replay = 1, extraBufferCapacity = 8)
    val updates: SharedFlow<StockGrossSnapshot> = _updates.asSharedFlow()

    fun initialize(scope: CoroutineScope) {
        scope.launch {
            stockSvc.updates.collect {
                _updates.tryEmit(StockGrossSnapshot(it.portfolioId, it.stockGrossUsd, it.stockGrossKnown))
            }
        }
    }
}
