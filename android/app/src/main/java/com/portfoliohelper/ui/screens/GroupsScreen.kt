package com.portfoliohelper.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portfoliohelper.MainViewModel
import com.portfoliohelper.data.model.GroupRow
import com.portfoliohelper.data.repository.YahooQuote
import com.portfoliohelper.ui.components.*
import com.portfoliohelper.ui.theme.ext

// ── Column indices — single source of truth ───────────────────────────────────
private const val COL_PNL = 0
private const val COL_WEIGHT = 1
private val COLUMN_LABELS = listOf("P&L", "Weight")
private val MAX_NAME_WIDTH = 140.dp

// ── Pre-computed display data for a single group ─────────────────────────────
private data class GroupDisplayData(
    val name: String,
    val dayPct: Double,
    val currentWeight: Double,
    val targetWeight: Double,
    val fmtValue: String,
    val fmtPnl: String,
    val pnlColor: Color,
    val isPnlDisplayCurrency: Boolean,
)

@Composable
private fun buildGroupDisplayData(
    group: GroupRow,
    displayCurrency: String,
    prices: Map<String, YahooQuote>,
    totalGrossValue: Double,
    pnlDisplayMode: String,
): GroupDisplayData {
    val usdToDisplayRate = if (displayCurrency == "USD") 1.0 else {
        val pair = "${displayCurrency}USD=X"
        val fxQuote = prices[pair]
        val rateToUsd = fxQuote?.regularMarketPrice ?: fxQuote?.previousClose
        if (rateToUsd != null && rateToUsd != 0.0) 1.0 / rateToUsd else 1.0
    }

    val mktValDisp = group.mktVal * usdToDisplayRate
    val prevMktValDisp = group.prevMktVal * usdToDisplayRate
    val mktValChgDisp = mktValDisp - prevMktValDisp
    
    val dayPct = if (prevMktValDisp != 0.0) (mktValChgDisp / prevMktValDisp * 100.0) else 0.0
    // Use mktValDisp for current weight calculation relative to totalGrossValue (already in display currency)
    val currentWeight = if (totalGrossValue > 0) (mktValDisp / totalGrossValue) * 100.0 else 0.0

    return GroupDisplayData(
        name = group.name,
        dayPct = dayPct,
        currentWeight = currentWeight,
        targetWeight = group.targetWeight,
        fmtValue = if (displayCurrency == "USD") formatCurrency(mktValDisp) else formatSmart(mktValDisp),
        fmtPnl = if (mktValChgDisp != 0.0) {
            (if (displayCurrency == "USD") formatSignedCurrency(mktValChgDisp) else formatSigned(mktValChgDisp))
        } else "—",
        pnlColor = changeColor(mktValChgDisp),
        isPnlDisplayCurrency = pnlDisplayMode != "NATIVE",
    )
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GroupsScreen(vm: MainViewModel) {
    val ext = MaterialTheme.ext
    val groups by vm.groupRows.collectAsState()
    val totals by vm.portfolioTotals.collectAsState()
    val cashTotals by vm.cashTotals.collectAsState()
    val displayCcy by vm.displayCurrency.collectAsState()
    val pnlMode by vm.pnlDisplayMode.collectAsState()
    val marketData by vm.marketData.collectAsState()

    val scrollState = rememberScrollState()

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(ext.bgPrimary)
    ) {
        val screenWidth = maxWidth

        // ── Build display data once — reused for measurement and row rendering ──
        val groupData = groups.map {
            buildGroupDisplayData(it, displayCcy, marketData, totals.stockGrossValue, pnlMode)
        }

        // Add a dummy data row used only for width measurement
        val widthMeasureData = groupData + GroupDisplayData(
            name = "Ex-US",
            dayPct = -1.23,
            currentWeight = 12.3,
            targetWeight = 15.0,
            fmtValue = "123,456.78",
            fmtPnl = "-1,234.56",
            pnlColor = Color.Red,
            isPnlDisplayCurrency = false,
        )

        val sampleName = widthMeasureData.maxBy { it.name.length }.name
        val samplePnlGroup = widthMeasureData.maxBy { 
            it.fmtPnl.length + formatSignedPct(it.dayPct).length
        }
        val sampleWeight = widthMeasureData.maxBy {
            val diffWeight = it.currentWeight - it.targetWeight
            val fmtCurWeight = "%.1f".format(it.currentWeight)
            val fmtTgtWeight = "%.1f".format(it.targetWeight)
            val fmtDiffWeight = "%.1f".format(diffWeight)
            fmtCurWeight.length + fmtTgtWeight.length + fmtDiffWeight.length + 8
        }

        MeasureTableLayout(
            screenWidth = screenWidth,
            frozenContent = {
                Text(
                    text = sampleName,
                    modifier = Modifier
                        .widthIn(max = MAX_NAME_WIDTH)
                        .padding(start = 12.dp, end = 8.dp),
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            columnContents = listOf(
                // COL_PNL
                {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        MonoText(
                            text = samplePnlGroup.fmtPnl,
                            fontWeight = FontWeight.Normal,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.width(4.dp))
                        DayPctPill(samplePnlGroup.dayPct)
                    }
                },
                // COL_WEIGHT
                {
                    WeightBreakdown(
                        current = sampleWeight.currentWeight,
                        target = sampleWeight.targetWeight,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            )
        ) { layout ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = layout.horizontalOffset),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // ── Summary cards ────────────────────────────────────────────
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val totalValue = totals.stockGrossValue + cashTotals.cashTotal
                        val prevTotalValue = totalValue - totals.dayChange
                        val totalChangePct = if (prevTotalValue != 0.0) {
                            (totals.dayChange / prevTotalValue) * 100.0
                        } else 0.0
                        val changeColor = changeColor(totals.dayChange)

                        SummaryCard(
                            label = "Portfolio Value",
                            value = if (totals.isReady) (if (displayCcy == "USD") formatCurrency(totalValue) else formatSmart(totalValue)) else "N/A",
                            subValue = if (totals.isReady) {
                                "${if (displayCcy == "USD") formatSignedCurrency(totals.dayChange) else formatSigned(totals.dayChange)} (${
                                    formatSignedPct(totalChangePct)
                                })"
                            } else null,
                            subValueColor = changeColor,
                            modifier = Modifier.weight(1f)
                        )
                        SummaryCard(
                            label = "Gross Value",
                            value = if (totals.isReady) (if (displayCcy == "USD") formatCurrency(totals.stockGrossValue) else formatSmart(totals.stockGrossValue)) else "N/A",
                            subValue = if (totals.isReady) {
                                "${if (displayCcy == "USD") formatSignedCurrency(totals.dayChange) else formatSigned(totals.dayChange)} (${
                                    formatSignedPct(totals.dayChangePct)
                                })"
                            } else null,
                            subValueColor = changeColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (groups.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No groups defined.\nAdd group tags to positions to see them here.",
                                color = ext.textTertiary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    // ── Table header ─────────────────────────────────────────
                    item {
                        TableHeader(
                            firstColumn = "Group" to layout.frozenWidth,
                            otherColumns = COLUMN_LABELS.zip(layout.columnWidths),
                            scrollState = if (layout.isScrollable) scrollState else null
                        )
                        Divider()
                    }

                    // ── Group rows ───────────────────────────────────────────
                    items(groupData, key = { it.name }) { display ->
                        GroupRow(
                            display = display,
                            scrollState = scrollState,
                            layout = layout
                        )
                        Divider()
                    }

                    item {
                        Text(
                            "⚠ Group values should be interpreted cautiously — their meaning depends on how groups are defined.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = ext.textTertiary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupRow(
    display: GroupDisplayData,
    scrollState: ScrollState,
    layout: TableLayout,
) {
    val ext = MaterialTheme.ext
    
    val pnlW = layout.columnWidths[COL_PNL]
    val weightW = layout.columnWidths[COL_WEIGHT]

    val scrollMod = if (layout.isScrollable)
        Modifier.horizontalScroll(scrollState) else Modifier

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ext.bgPrimary)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Frozen column: Name
        Text(
            text = display.name,
            modifier = Modifier
                .width(layout.frozenWidth)
                .padding(start = 12.dp, end = 8.dp),
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            color = ext.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Scrollable columns
        Row(
            modifier = scrollMod.padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // P&L + Day %
            Row(
                modifier = Modifier.width(pnlW),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonoText(
                    text = display.fmtPnl,
                    color = display.pnlColor,
                    fontWeight = FontWeight.Normal,
                    fontStyle = if (display.isPnlDisplayCurrency) FontStyle.Italic else FontStyle.Normal,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.width(4.dp))
                DayPctPill(display.dayPct)
            }

            // Weight breakdown
            WeightBreakdown(
                current = display.currentWeight,
                target = display.targetWeight,
                modifier = Modifier.width(weightW),
            )
        }
    }
}
