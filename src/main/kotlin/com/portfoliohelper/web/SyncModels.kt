package com.portfoliohelper.web

import kotlinx.serialization.Serializable

@Serializable
data class PositionDto(
    val symbol: String,
    val quantity: Double,
    val targetWeight: Double,
    val groups: String
)

@Serializable
data class CashDto(
    val label: String,
    val currency: String,
    val amount: Double,
    val isMargin: Boolean
)

@Serializable
data class SyncData(
    val positions: List<PositionDto>,
    val cash: List<CashDto>
)
