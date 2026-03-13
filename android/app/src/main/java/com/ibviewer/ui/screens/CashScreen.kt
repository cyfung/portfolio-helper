package com.ibviewer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ibviewer.MainViewModel
import com.ibviewer.data.model.CashEntry
import com.ibviewer.ui.components.*
import com.ibviewer.ui.theme.ext
import kotlin.math.abs

@Composable
fun CashScreen(vm: MainViewModel) {
    val ext         = MaterialTheme.ext
    val cashEntries by vm.cashEntries.collectAsState()
    val cashTotals  by vm.cashTotals.collectAsState()
    val totals      by vm.portfolioTotals.collectAsState()
    val fxRates     by vm.fxRates.collectAsState()

    var showAddDialog  by remember { mutableStateOf(false) }
    var showFxDialog   by remember { mutableStateOf(false) }
    var editEntry      by remember { mutableStateOf<CashEntry?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick        = { showAddDialog = true },
                containerColor = ext.actionPositive
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add cash entry",
                    tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier       = Modifier.fillMaxSize().background(ext.bgPrimary).padding(padding),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // ── Summary cards ────────────────────────────────────────────────
            item {
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryCard("Cash Total",
                        formatCurrency(cashTotals.totalUsd), modifier = Modifier.weight(1f))

                    val marginUsd = cashTotals.marginUsd
                    val equity    = totals.totalMktVal + marginUsd
                    val marginPct = if (equity != 0.0) abs(marginUsd / equity) * 100.0 else 0.0
                    val marginColor = when {
                        marginPct > 40.0 -> ext.negative
                        marginPct > 20.0 -> ext.warning
                        else             -> ext.textPrimary
                    }
                    SummaryCard("Margin",
                        "${formatCurrency(abs(marginUsd))} (${formatPct(marginPct, 1)})",
                        valueColor = if (marginUsd < 0) marginColor else ext.textTertiary,
                        modifier   = Modifier.weight(1f))
                }
            }

            // ── FX rates button ───────────────────────────────────────────────
            item {
                TextButton(
                    onClick  = { showFxDialog = true },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text("Edit FX Rates", fontSize = 12.sp, color = ext.actionPositive)
                }
            }

            // ── Table header ──────────────────────────────────────────────────
            item {
                TableHeader(listOf("Label" to 1.8f, "CCY" to 0.6f, "Amount" to 1.2f, "USD" to 1.2f))
                Divider()
            }

            // ── Cash rows ─────────────────────────────────────────────────────
            items(cashEntries, key = { it.id }) { entry ->
                CashEntryRow(
                    entry    = entry,
                    fxRates  = fxRates,
                    onEdit   = { editEntry = entry },
                    onDelete = { vm.deleteCashEntry(entry) }
                )
                Divider()
            }
        }
    }

    if (showAddDialog) {
        CashEntryDialog(
            initial   = null,
            onDismiss = { showAddDialog = false },
            onSave    = { vm.upsertCashEntry(it); showAddDialog = false }
        )
    }

    editEntry?.let { entry ->
        CashEntryDialog(
            initial   = entry,
            onDismiss = { editEntry = null },
            onSave    = { vm.upsertCashEntry(it); editEntry = null }
        )
    }

    if (showFxDialog) {
        FxRatesDialog(
            current   = fxRates,
            onDismiss = { showFxDialog = false },
            onSave    = { vm.saveFxRates(it); showFxDialog = false }
        )
    }
}

@Composable
fun CashEntryRow(
    entry: CashEntry,
    fxRates: Map<String, Double>,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val ext  = MaterialTheme.ext
    val rate = if (entry.currency == "USD") 1.0 else fxRates[entry.currency]
    val usd  = rate?.let { entry.amount * it }

    var showActions by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (entry.isMargin) ext.bgSecondary else ext.bgPrimary)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1.8f)) {
                Text(entry.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ext.textPrimary)
                if (entry.isMargin) Text("MARGIN", fontSize = 9.sp, color = ext.negative, fontWeight = FontWeight.Bold)
            }
            MonoText(entry.currency, color = ext.textTertiary, modifier = Modifier.weight(0.6f))
            MonoText(
                formatCurrency(abs(entry.amount)),
                color    = if (entry.amount < 0) ext.negative else ext.textSecondary,
                modifier = Modifier.weight(1.2f)
            )
            MonoText(
                if (usd != null) formatCurrency(abs(usd)) else "N/A",
                color    = if (usd != null && usd < 0) ext.negative else ext.textTertiary,
                modifier = Modifier.weight(1.2f)
            )
        }

        if (showActions) {
            Row(
                modifier              = Modifier.fillMaxWidth().background(ext.bgSecondary)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { onEdit(); showActions = false }) {
                    Text("Edit", fontSize = 12.sp)
                }
                TextButton(onClick = { onDelete(); showActions = false }) {
                    Text("Delete", fontSize = 12.sp, color = ext.negative)
                }
            }
        }
    }
}

@Composable
fun CashEntryDialog(initial: CashEntry?, onDismiss: () -> Unit, onSave: (CashEntry) -> Unit) {
    var label    by remember { mutableStateOf(initial?.label ?: "") }
    var currency by remember { mutableStateOf(initial?.currency ?: "USD") }
    var amount   by remember { mutableStateOf(initial?.amount?.toString() ?: "") }
    var isMargin by remember { mutableStateOf(initial?.isMargin ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Cash Entry" else "Edit Entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label,    { label    = it }, label = { Text("Label") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(currency, { currency = it.uppercase() }, label = { Text("Currency") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(amount,   { amount   = it }, label = { Text("Amount (negative = loan)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(isMargin, { isMargin = it })
                    Text("Margin / Loan", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val entry = CashEntry(
                    id       = initial?.id ?: 0,
                    label    = label.trim(),
                    currency = currency.trim().uppercase(),
                    amount   = amount.toDoubleOrNull() ?: 0.0,
                    isMargin = isMargin
                )
                if (entry.label.isNotEmpty()) onSave(entry)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun FxRatesDialog(
    current: Map<String, Double>,
    onDismiss: () -> Unit,
    onSave: (Map<String, Double>) -> Unit
) {
    var text by remember {
        mutableStateOf(current.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("FX Rates (to USD)") },
        text = {
            Column {
                Text("One per line: CCY=rate\ne.g. HKD=0.1282",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.ext.textTertiary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().height(160.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val map = text.lines()
                    .mapNotNull { line ->
                        val parts = line.trim().split("=")
                        if (parts.size == 2) {
                            val ccy  = parts[0].trim().uppercase()
                            val rate = parts[1].trim().toDoubleOrNull()
                            if (ccy.isNotEmpty() && rate != null) ccy to rate else null
                        } else null
                    }.toMap()
                onSave(map)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
