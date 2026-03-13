package com.ibviewer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ibviewer.MainViewModel
import com.ibviewer.data.model.Position
import com.ibviewer.data.repository.YahooQuote
import com.ibviewer.ui.components.DayPctPill
import com.ibviewer.ui.components.Divider
import com.ibviewer.ui.components.MonoText
import com.ibviewer.ui.components.SummaryCard
import com.ibviewer.ui.components.TableHeader
import com.ibviewer.ui.components.changeColor
import com.ibviewer.ui.components.formatCurrency
import com.ibviewer.ui.components.formatSignedCurrency
import com.ibviewer.ui.components.formatSignedPct
import com.ibviewer.ui.theme.ext
import kotlin.math.abs

@Composable
fun PortfolioScreen(vm: MainViewModel) {
    val ext = MaterialTheme.ext
    val positions by vm.positions.collectAsState()
    val marketData by vm.marketData.collectAsState()
    val totals by vm.portfolioTotals.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editPosition by remember { mutableStateOf<Position?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = ext.actionPositive
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add position",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(ext.bgPrimary)
                .padding(padding),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // ── Summary cards ────────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryCard(
                        label = "Portfolio Value",
                        value = formatCurrency(totals.totalMktVal),
                        modifier = Modifier.weight(1f)
                    )
                    val changeColor = changeColor(totals.dayChangeDollars)
                    SummaryCard(
                        label = "Day Change",
                        value = "${formatSignedCurrency(totals.dayChangeDollars)} (${
                            formatSignedPct(
                                totals.dayChangePct
                            )
                        })",
                        valueColor = changeColor,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Table header ─────────────────────────────────────────────────
            item {
                TableHeader(
                    listOf(
                        "Symbol" to 1.4f,
                        "Mark" to 1.3f,
                        "Day %" to 0.9f,
                        "Mkt Val" to 1.3f,
                        "Weight %" to 1.1f
                    )
                )
                Divider()
            }

            // ── Position rows ─────────────────────────────────────────────────
            items(positions, key = { it.symbol }) { pos ->
                val quote = marketData[pos.symbol]
                PositionRow(
                    pos = pos,
                    quote = quote,
                    portfolioVal = totals.totalMktVal,
                    onEdit = { editPosition = pos },
                    onDelete = { vm.deletePosition(pos.symbol) }
                )
                Divider()
            }
        }
    }

    if (showAddDialog) {
        PositionDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { vm.upsertPosition(it); showAddDialog = false }
        )
    }

    editPosition?.let { pos ->
        PositionDialog(
            initial = pos,
            onDismiss = { editPosition = null },
            onSave = { vm.upsertPosition(it); editPosition = null }
        )
    }
}

@Composable
fun PositionRow(
    pos: Position,
    quote: YahooQuote?,
    portfolioVal: Double,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val ext = MaterialTheme.ext
    val mark = quote?.regularMarketPrice ?: quote?.previousClose
    val close = quote?.previousClose ?: quote?.regularMarketPrice
    val dayPct = if (mark != null && close != null && close != 0.0)
        (mark - close) / close * 100.0 else null
    val mktVal = (mark ?: 0.0) * pos.quantity
    val weightPct = if (portfolioVal > 0) mktVal / portfolioVal * 100.0 else 0.0

    var showActions by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ext.bgPrimary)
            .clickable { showActions = !showActions }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Symbol + qty
        Column(modifier = Modifier.weight(1.4f)) {
            Text(
                pos.symbol,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = ext.textPrimary
            )
            Text(
                "×${pos.quantity}",
                fontSize = 10.sp,
                color = ext.textTertiary,
                fontFamily = FontFamily.Monospace
            )
        }

        // Mark price
        Column(modifier = Modifier.weight(1.3f), horizontalAlignment = Alignment.End) {
            MonoText(
                text = if (mark != null) formatCurrency(mark) else "—",
                color = ext.textSecondary
            )
        }

        // Day %
        Box(modifier = Modifier.weight(0.9f), contentAlignment = Alignment.CenterEnd) {
            if (dayPct != null) DayPctPill(dayPct)
            else MonoText("—", color = ext.textTertiary)
        }

        // Mkt Val
        MonoText(
            text = formatCurrency(mktVal),
            color = ext.textSecondary,
            modifier = Modifier.weight(1.3f),
        )

        // Weight %
        MonoText(
            text = "${weightPct.toFixed(1)}%",
            color = ext.textSecondary,
            modifier = Modifier.weight(1.1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }

    // Inline action row
    if (showActions) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ext.bgSecondary)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { onEdit(); showActions = false }) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Edit", fontSize = 12.sp)
            }
            TextButton(onClick = { onDelete(); showActions = false }) {
                Icon(
                    Icons.Default.Delete, contentDescription = null,
                    modifier = Modifier.size(16.dp), tint = MaterialTheme.ext.negative
                )
                Spacer(Modifier.width(4.dp))
                Text("Delete", fontSize = 12.sp, color = MaterialTheme.ext.negative)
            }
        }
    }
}

@Composable
fun PositionDialog(
    initial: Position?,
    onDismiss: () -> Unit,
    onSave: (Position) -> Unit
) {
    var symbol by remember { mutableStateOf(initial?.symbol ?: "") }
    var qty by remember { mutableStateOf(initial?.quantity?.toString() ?: "") }
    var targetWt by remember { mutableStateOf(initial?.targetWeight?.toString() ?: "") }
    var groups by remember { mutableStateOf(initial?.groups ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Position" else "Edit ${initial.symbol}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    symbol, { symbol = it.uppercase() }, label = { Text("Symbol") },
                    enabled = initial == null, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    qty, { qty = it }, label = { Text("Quantity") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    targetWt, { targetWt = it }, label = { Text("Target Weight %") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    groups,
                    { groups = it },
                    label = { Text("Groups (e.g. '1.0 Tech; 0.5 Growth')") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val pos = Position(
                    symbol = symbol.trim().uppercase(),
                    quantity = qty.toDoubleOrNull() ?: 0.0,
                    targetWeight = targetWt.toDoubleOrNull() ?: 0.0,
                    groups = groups.trim()
                )
                if (pos.symbol.isNotEmpty()) onSave(pos)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun Double.toFixed(n: Int) = "%.${n}f".format(this)
