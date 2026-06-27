package com.portfoliohelper.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portfoliohelper.MainViewModel
import com.portfoliohelper.data.model.Position
import com.portfoliohelper.data.repository.YahooQuote
import com.portfoliohelper.ui.components.Divider
import com.portfoliohelper.ui.components.MeasureTableLayout
import com.portfoliohelper.ui.components.MonoText
import com.portfoliohelper.ui.components.SummaryCard
import com.portfoliohelper.ui.components.TableHeader
import com.portfoliohelper.ui.components.TableLayout
import com.portfoliohelper.ui.components.WeightBreakdown
import com.portfoliohelper.ui.components.changeColor
import com.portfoliohelper.ui.components.formatCurrency
import com.portfoliohelper.ui.components.formatPct
import com.portfoliohelper.ui.components.formatSigned
import com.portfoliohelper.ui.components.formatSignedCurrency
import com.portfoliohelper.ui.components.formatSignedPct
import com.portfoliohelper.ui.components.formatSmart
import com.portfoliohelper.ui.theme.ext
import kotlin.math.abs
import kotlin.math.round

private const val COL_WEIGHT = 0
private const val COL_REBAL_DOLLARS = 1
private const val COL_REBAL_QTY = 2
private val COLUMN_LABELS = listOf("Weight", "Rebal $", "Rebal Qty")

private data class RebalanceStockDisplayData(
    val symbol: String,
    val currentWeight: Double,
    val targetWeight: Double,
    val rebalDollars: Double?,
    val rebalQty: Double?,
)

@Composable
fun RebalanceScreen(vm: MainViewModel) {
    val ext = MaterialTheme.ext
    val positions by vm.positions.collectAsState()
    val marketData by vm.marketData.collectAsState()
    val totals by vm.portfolioTotals.collectAsState()
    val totalsUsd by vm.portfolioTotalsUsd.collectAsState()
    val cashTotals by vm.cashTotals.collectAsState()
    val displayCcy by vm.displayCurrency.collectAsState()
    val scaling by vm.scalingPercent.collectAsState()
    val targetMarginPct by vm.rebalanceTargetMarginPct.collectAsState()

    val scrollState = rememberScrollState()
    val hasTargetWeights = positions.any { it.targetWeight > 0 }
    val stockGrossReady = totalsUsd.isReady
    val rebalTotalUsd = getRebalTotalFromMarginTarget(
        targetMarginPct = targetMarginPct,
        stockGrossUsd = totalsUsd.stockGrossValue,
        marginUsd = totalsUsd.margin,
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ext.bgPrimary)
    ) {
        val screenWidth = maxWidth
        val stockData = positions.map { pos ->
            buildRebalanceStockDisplayData(
                pos = pos,
                quote = marketData[pos.symbol],
                prices = marketData,
                stockGrossUsd = totalsUsd.stockGrossValue,
                rebalTotalUsd = rebalTotalUsd,
                scaling = scaling,
                hasTargetWeights = hasTargetWeights,
            )
        }

        val widthMeasureData = stockData + RebalanceStockDisplayData(
            symbol = "WWWW.PA",
            currentWeight = 22.2,
            targetWeight = 99.9,
            rebalDollars = -88888.88,
            rebalQty = -888.88,
        )
        val sampleSymbol = widthMeasureData.maxBy { it.symbol.length }.symbol
        val sampleWeight = widthMeasureData.maxBy {
            val diffWeight = it.currentWeight - it.targetWeight
            "%.1f%.1f%.1f".format(it.currentWeight, it.targetWeight, diffWeight).length
        }
        val sampleRebalDollars = widthMeasureData.maxBy {
            (it.rebalDollars?.let(::formatSigned) ?: "---").length
        }.rebalDollars ?: 0.0
        val sampleRebalQty = widthMeasureData.maxBy {
            (it.rebalQty?.let { qty -> "%+.2f".format(qty) } ?: "---").length
        }.rebalQty ?: 0.0

        MeasureTableLayout(
            screenWidth = screenWidth,
            frozenContent = {
                Text(
                    text = sampleSymbol,
                    modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                )
            },
            columnContents = listOf(
                {
                    WeightBreakdown(
                        current = sampleWeight.currentWeight,
                        target = sampleWeight.targetWeight,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                },
                {
                    MonoText(
                        text = formatSigned(sampleRebalDollars),
                        fontWeight = FontWeight.Normal,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                },
                {
                    MonoText(
                        text = "%+.2f".format(sampleRebalQty),
                        fontWeight = FontWeight.Normal,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                },
            ),
        ) { layout ->
            Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(start = layout.horizontalOffset),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        RebalanceSummaryCards(
                            totalsReady = totals.isReady,
                            stockGrossReady = stockGrossReady,
                            totalValue = totals.stockGrossValue + cashTotals.cashTotal,
                            grossValue = totals.stockGrossValue,
                            dayChange = totals.dayChange,
                            dayChangePct = totals.dayChangePct,
                            margin = cashTotals.margin,
                            marginPct = totals.marginPct,
                            displayCcy = displayCcy,
                            targetMarginPct = targetMarginPct,
                            onTargetMarginChange = vm::saveRebalanceTargetMarginPct,
                        )
                    }
                    item {
                        TableHeader(
                            firstColumn = "Symbol" to layout.frozenWidth,
                            otherColumns = COLUMN_LABELS.zip(layout.columnWidths),
                            scrollState = if (layout.isScrollable) scrollState else null,
                        )
                        Divider()
                    }
                    items(positions, key = { it.symbol }) { pos ->
                        val display = stockData.firstOrNull { it.symbol == pos.symbol } ?: return@items
                        RebalancePositionRow(
                            pos = pos,
                            display = display,
                            scrollState = scrollState,
                            layout = layout,
                        )
                        Divider()
                    }
                    if (!stockGrossReady || !hasTargetWeights) {
                        item {
                            Text(
                                text = if (!hasTargetWeights) {
                                    "Set target weights to calculate rebalancing."
                                } else {
                                    "Waiting for complete market data."
                                },
                                color = ext.textTertiary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RebalanceSummaryCards(
    totalsReady: Boolean,
    stockGrossReady: Boolean,
    totalValue: Double,
    grossValue: Double,
    dayChange: Double,
    dayChangePct: Double,
    margin: Double,
    marginPct: Double,
    displayCcy: String,
    targetMarginPct: Double?,
    onTargetMarginChange: (Double?) -> Unit,
) {
    val ext = MaterialTheme.ext
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val prevTotalValue = totalValue - dayChange
            val totalChangePct = if (prevTotalValue != 0.0) (dayChange / prevTotalValue) * 100.0 else 0.0
            val changeColor = changeColor(dayChange)
            SummaryCard(
                label = "Portfolio Value",
                value = if (totalsReady) formatMoney(totalValue, displayCcy) else "N/A",
                subValue = if (totalsReady) "${formatSignedMoney(dayChange, displayCcy)} (${formatSignedPct(totalChangePct)})" else null,
                subValueColor = changeColor,
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                label = "Gross Value",
                value = if (totalsReady) formatMoney(grossValue, displayCcy) else "N/A",
                subValue = if (totalsReady) "${formatSignedMoney(dayChange, displayCcy)} (${formatSignedPct(dayChangePct)})" else null,
                subValueColor = changeColor,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (totalsReady) {
                if (margin >= 0) {
                    SummaryCard("Margin", "-", valueColor = ext.textPrimary, subValue = "", modifier = Modifier.weight(1f))
                } else {
                    SummaryCard(
                        "Margin",
                        formatSmart(abs(margin)),
                        valueColor = ext.warning,
                        subValue = formatPct(marginPct, 1),
                        subValueColor = ext.warning,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                SummaryCard("Margin", "N/A", valueColor = ext.textPrimary, subValue = "", modifier = Modifier.weight(1f))
            }
            TargetMarginCard(
                value = targetMarginPct,
                enabled = true,
                onValueChange = onTargetMarginChange,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TargetMarginCard(
    value: Double?,
    enabled: Boolean,
    onValueChange: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = MaterialTheme.ext
    var text by remember(value) {
        mutableStateOf(value?.toBigDecimal()?.stripTrailingZeros()?.toPlainString() ?: "")
    }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val inputShape = RoundedCornerShape(6.dp)
    val inputBorderColor = when {
        !enabled -> ext.textTertiary.copy(alpha = 0.1f)
        focused -> ext.actionPositive.copy(alpha = 0.5f)
        else -> ext.textTertiary.copy(alpha = 0.2f)
    }
    val inputTextColor = if (enabled) ext.textPrimary else ext.textTertiary

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = ext.bgElevated,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                "Target Margin %",
                style = MaterialTheme.typography.labelSmall,
                color = ext.textTertiary,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(4.dp))
            BasicTextField(
                value = text,
                onValueChange = { next ->
                    if (next.all { it.isDigit() || it == '.' } && next.count { it == '.' } <= 1) {
                        text = next
                        when {
                            next.isBlank() -> onValueChange(null)
                            next.lastOrNull() != '.' -> onValueChange(next.toDoubleOrNull()?.takeIf { it > 0 })
                        }
                    }
                },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = LocalTextStyle.current.copy(
                    color = inputTextColor,
                    fontSize = 15.sp,
                    textAlign = TextAlign.End
                ),
                interactionSource = interactionSource,
                cursorBrush = SolidColor(ext.actionPositive),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .border(1.dp, inputBorderColor, inputShape)
                    .padding(horizontal = 10.dp),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            if (text.isEmpty()) {
                                Text("Off", fontSize = 13.sp, color = ext.textTertiary)
                            }
                            innerTextField()
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("%", fontSize = 13.sp, color = ext.textTertiary)
                    }
                }
            )
        }
    }
}

@Composable
private fun RebalancePositionRow(
    pos: Position,
    display: RebalanceStockDisplayData,
    scrollState: ScrollState,
    layout: TableLayout,
) {
    val ext = MaterialTheme.ext
    val weightW = layout.columnWidths[COL_WEIGHT]
    val rebalDollarsW = layout.columnWidths[COL_REBAL_DOLLARS]
    val rebalQtyW = layout.columnWidths[COL_REBAL_QTY]
    val scrollMod = if (layout.isScrollable) Modifier.horizontalScroll(scrollState) else Modifier

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ext.bgPrimary)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            pos.symbol,
            modifier = Modifier
                .width(layout.frozenWidth)
                .padding(start = 12.dp),
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            color = ext.textPrimary
        )
        Row(
            modifier = scrollMod.padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WeightBreakdown(
                current = display.currentWeight,
                target = display.targetWeight,
                modifier = Modifier.width(weightW),
            )
            MonoText(
                text = display.rebalDollars?.let(::formatSigned) ?: "-",
                color = display.rebalDollars?.let { actionColor(it) } ?: ext.textTertiary,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                modifier = Modifier.width(rebalDollarsW),
            )
            MonoText(
                text = display.rebalQty?.let { "%+.2f".format(it) } ?: "-",
                color = display.rebalQty?.let { actionColor(it) } ?: ext.textTertiary,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                modifier = Modifier.width(rebalQtyW),
            )
        }
    }
}

@Composable
private fun actionColor(value: Double): Color {
    val ext = MaterialTheme.ext
    return when {
        value > 0.01 -> ext.actionPositive
        value < -0.01 -> ext.actionNegative
        else -> ext.textTertiary
    }
}

private fun buildRebalanceStockDisplayData(
    pos: Position,
    quote: YahooQuote?,
    prices: Map<String, YahooQuote>,
    stockGrossUsd: Double,
    rebalTotalUsd: Double,
    scaling: Int?,
    hasTargetWeights: Boolean,
): RebalanceStockDisplayData {
    val rawMark = quote?.regularMarketPrice ?: quote?.previousClose
    val multiplierToUsd = quote?.let { quoteToUsdMultiplier(it, prices) }
    val scaledQty = if (scaling != null) round(pos.quantity * scaling / 100.0) else pos.quantity
    val priceUsd = if (rawMark != null && multiplierToUsd != null) rawMark * multiplierToUsd else null
    val positionValueUsd = if (priceUsd != null) priceUsd * scaledQty else 0.0
    val currentWeight = if (stockGrossUsd > 0) (positionValueUsd / stockGrossUsd) * 100.0 else 0.0

    val rebalUsd = if (hasTargetWeights && priceUsd != null && stockGrossUsd > 0) {
        (pos.targetWeight / 100.0) * rebalTotalUsd - positionValueUsd
    } else null
    val rebalNative = if (rebalUsd != null && multiplierToUsd != null && multiplierToUsd != 0.0) {
        rebalUsd / multiplierToUsd
    } else null
    val rebalQty = if (rebalUsd != null && priceUsd != null && priceUsd > 0.0) rebalUsd / priceUsd else null

    return RebalanceStockDisplayData(
        symbol = pos.symbol,
        currentWeight = currentWeight,
        targetWeight = pos.targetWeight,
        rebalDollars = rebalNative,
        rebalQty = rebalQty,
    )
}

private fun quoteToUsdMultiplier(quote: YahooQuote, prices: Map<String, YahooQuote>): Double? {
    val currency = quote.currency ?: "USD"
    val isPence = currency.length == 3 && currency[2].isLowerCase()
    val normalizedCcy = if (isPence) currency.uppercase() else currency
    val rateToUsd = if (normalizedCcy == "USD") 1.0 else {
        val pair = "${normalizedCcy}USD=X"
        prices[pair]?.let { it.regularMarketPrice ?: it.previousClose } ?: return null
    }
    return if (isPence) rateToUsd / 100.0 else rateToUsd
}

private fun getRebalTotalFromMarginTarget(
    targetMarginPct: Double?,
    stockGrossUsd: Double,
    marginUsd: Double,
): Double {
    if (targetMarginPct != null && targetMarginPct > 0) {
        val equity = stockGrossUsd + marginUsd
        return (targetMarginPct / 100.0) * equity + stockGrossUsd + marginUsd
    }
    return stockGrossUsd + maxOf(marginUsd, 0.0)
}

private fun formatMoney(value: Double, displayCcy: String): String =
    if (displayCcy == "USD") formatCurrency(value) else formatSmart(value)

private fun formatSignedMoney(value: Double, displayCcy: String): String =
    if (displayCcy == "USD") formatSignedCurrency(value) else formatSigned(value)
