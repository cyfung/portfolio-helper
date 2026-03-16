package com.portfoliohelper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portfoliohelper.MainViewModel
import com.portfoliohelper.data.model.GroupRow
import com.portfoliohelper.ui.components.*
import com.portfoliohelper.ui.theme.ext

@Composable
fun GroupsScreen(vm: MainViewModel) {
    val ext = MaterialTheme.ext
    val groups by vm.groupRows.collectAsState()
    val totals by vm.portfolioTotals.collectAsState()
    val cashTotals by vm.cashTotals.collectAsState()
    val displayCcy by vm.displayCurrency.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(ext.bgPrimary),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // ── Summary cards ────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val totalValue = totals.stockGrossValue + cashTotals.totalUsd
                val prevTotalValue = totalValue - totals.dayChange
                val totalChangePct = if (prevTotalValue != 0.0) {
                    (totals.dayChange / prevTotalValue) * 100.0
                } else 0.0

                val changeColor = changeColor(totals.dayChange)
                val ccySuffix = if (displayCcy != "USD") " $displayCcy" else ""

                SummaryCard(
                    label = "Portfolio Value",
                    value = if (totals.isReady) (if (displayCcy == "USD") formatCurrency(totalValue) else formatSmart(totalValue)) + ccySuffix else "N/A",
                    subValue = if (totals.isReady) {
                        "${if (displayCcy == "USD") formatSignedCurrency(totals.dayChange) else formatSigned(totals.dayChange) + ccySuffix} (${
                            formatSignedPct(totalChangePct)
                        })"
                    } else null,
                    subValueColor = changeColor,
                    modifier = Modifier.weight(1f)
                )
                SummaryCard(
                    label = "Gross Value",
                    value = if (totals.isReady) (if (displayCcy == "USD") formatCurrency(totals.stockGrossValue) else formatSmart(totals.stockGrossValue)) + ccySuffix else "N/A",
                    subValue = if (totals.isReady) {
                        "${if (displayCcy == "USD") formatSignedCurrency(totals.dayChange) else formatSigned(totals.dayChange) + ccySuffix} (${
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
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp),
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
            item {
                TableHeader(
                    listOf(
                        "Group" to 1.5f,
                        "CHG %" to 1.2f,
                        "P&L" to 1.5f
                    )
                )
                Divider()
            }

            items(groups, key = { it.name }) { group ->
                GroupRow(
                    group = group,
                    displayCurrency = displayCcy,
                    prices = vm.marketData.collectAsState().value
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

@Composable
fun GroupRow(
    group: GroupRow,
    displayCurrency: String,
    prices: Map<String, com.portfoliohelper.data.repository.YahooQuote>
) {
    val ext = MaterialTheme.ext
    
    // Group values are computed in USD in PortfolioCalculator.computeGroups
    // We need to convert them to displayCurrency
    val usdToDisplayRate = if (displayCurrency == "USD") 1.0 else {
        val pair = "${displayCurrency}USD=X"
        val fxQuote = prices[pair]
        val rateToUsd = fxQuote?.regularMarketPrice ?: fxQuote?.previousClose
        if (rateToUsd != null && rateToUsd != 0.0) 1.0 / rateToUsd else 1.0
    }

    val mktValDisp = group.mktVal * usdToDisplayRate
    val prevMktValDisp = group.prevMktVal * usdToDisplayRate
    val mktValChgDisp = mktValDisp - prevMktValDisp
    
    val dayPct = if (group.prevMktVal > 0) (mktValChgDisp / prevMktValDisp * 100.0) else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ext.bgPrimary)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Group name
        Text(
            text = group.name,
            modifier = Modifier.weight(1.5f),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = ext.textPrimary
        )

        // CHG %
        Box(modifier = Modifier.weight(1.2f), contentAlignment = Alignment.CenterEnd) {
            if (dayPct != null) DayPctPill(dayPct)
            else MonoText("—", color = ext.textTertiary, fontSize = 15.sp)
        }

        // P&L
        val pnlColor = changeColor(mktValChgDisp)
        val ccySuffix = if (displayCurrency != "USD") " $displayCurrency" else ""
        
        MonoText(
            text = if (mktValChgDisp != 0.0) {
                (if (displayCurrency == "USD") formatSignedCurrency(mktValChgDisp) else formatSigned(mktValChgDisp) + ccySuffix)
            } else "—",
            color = pnlColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.weight(1.5f)
        )
    }
}
