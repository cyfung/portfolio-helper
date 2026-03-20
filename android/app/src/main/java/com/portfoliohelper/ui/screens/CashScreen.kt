package com.portfoliohelper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portfoliohelper.MainViewModel
import com.portfoliohelper.data.model.CashEntry
import com.portfoliohelper.ui.components.CashTypeBadge
import com.portfoliohelper.ui.components.Divider
import com.portfoliohelper.ui.components.MonoText
import com.portfoliohelper.ui.components.SummaryCard
import com.portfoliohelper.ui.components.formatCurrency
import com.portfoliohelper.ui.components.formatPct
import com.portfoliohelper.ui.components.formatSmart
import com.portfoliohelper.ui.theme.ext
import java.util.Locale
import kotlin.math.abs

@Composable
fun CashScreen(vm: MainViewModel) {
    val ext = MaterialTheme.ext
    val cashEntries by vm.cashEntries.collectAsState()
    val cashTotals by vm.cashTotals.collectAsState()
    val totals by vm.portfolioTotals.collectAsState()
    val fxRates by vm.fxRates.collectAsState()
    val stockValues by vm.allPortfolioStockValuesUsd.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf<CashEntry?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // handled by Scaffold in MainActivity
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = ext.actionPositive
            ) {
                Icon(
                    Icons.Default.Add, contentDescription = "Add cash entry",
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
                    if (cashTotals.isReady) {
                        val totalUsd = cashTotals.totalUsd
                        SummaryCard(
                            "Cash Total",
                            formatCurrency(totalUsd),
                            valueColor = if (totalUsd < 0) ext.negative else ext.textPrimary,
                            subValue = "",
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        SummaryCard(
                            "Cash Total",
                            "N/A",
                            subValue = "",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (totals.isReady) {
                        val marginUsd = cashTotals.marginUsd
                        val marginPct = totals.marginPct
                        if (marginUsd >= 0) {
                            SummaryCard(
                                "Margin",
                                "-",
                                valueColor = ext.textPrimary,
                                subValue = "",
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            SummaryCard(
                                "Margin",
                                formatSmart(abs(marginUsd)),
                                valueColor = ext.warning,
                                subValue = formatPct(marginPct, 1),
                                subValueColor = ext.warning,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        SummaryCard(
                            "Margin",
                            "N/A",
                            valueColor = ext.textPrimary,
                            subValue = "",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ── Table header ──────────────────────────────────────────────────
            item {
                Divider()
            }

            // ── Cash rows ─────────────────────────────────────────────────────
            items(cashEntries, key = { it.id }) { entry ->
                CashEntryRow(
                    entry = entry,
                    fxRates = fxRates,
                    stockValues = stockValues,
                    onEdit = { editEntry = entry },
                    onDelete = { vm.deleteCashEntry(entry) }
                )
                Divider()
            }
        }
    }

    if (showAddDialog) {
        CashEntryDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { vm.upsertCashEntry(it); showAddDialog = false }
        )
    }

    editEntry?.let { entry ->
        CashEntryDialog(
            initial = entry,
            onDismiss = { editEntry = null },
            onSave = { vm.upsertCashEntry(it); editEntry = null }
        )
    }
}

@Composable
fun CashEntryRow(
    entry: CashEntry,
    fxRates: Map<String, Double>,
    stockValues: Map<String, Pair<Double, Boolean>>,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val ext = MaterialTheme.ext
    val usd = if (entry.currency == "P") {
        stockValues[entry.portfolioRef]?.let { it.first * entry.amount }
    } else {
        val rate = if (entry.currency == "USD") 1.0 else fxRates[entry.currency]
        rate?.let { entry.amount * it }
    }

    var showActions by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ext.bgPrimary)
                .clickable { showActions = !showActions }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Label
            Column(modifier = Modifier.weight(1.5f)) {
                Text(
                    text = entry.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = ext.textPrimary
                )
                if (entry.currency == "P") {
                    Text(
                        text = "Ref: ${entry.portfolioRef ?: "None"}",
                        fontSize = 11.sp,
                        color = ext.textTertiary
                    )
                }
            }

            // Margin Badge
            Box(modifier = Modifier.weight(0.4f), contentAlignment = Alignment.CenterStart) {
                if (entry.isMargin) CashTypeBadge("M")
            }

            // Raw Amount + Currency on same line
            Row(
                modifier = Modifier.weight(1.5f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonoText(
                    text = if (entry.currency == "P") "%.2fx".format(entry.amount) else "%,.2f".format(Locale.US, entry.amount),
                    color = ext.textTertiary,
                    fontWeight = FontWeight.Normal,
                    fontSize = 16.sp
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (entry.currency == "P") "PORT" else entry.currency,
                    fontSize = 11.sp,
                    color = ext.textTertiary,
                    fontWeight = FontWeight.Medium
                )
            }

            // USD Converted
            MonoText(
                text = if (usd != null) formatCurrency(usd) else "—",
                color = ext.textSecondary,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                modifier = Modifier.weight(1.3f)
            )
        }

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
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Edit", fontSize = 12.sp)
                }
                TextButton(onClick = { onDelete(); showActions = false }) {
                    Icon(
                        Icons.Default.Delete, contentDescription = null,
                        modifier = Modifier.size(16.dp), tint = ext.negative
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", fontSize = 12.sp, color = ext.negative)
                }
            }
        }
    }
}

@Composable
fun CashEntryDialog(initial: CashEntry?, onDismiss: () -> Unit, onSave: (CashEntry) -> Unit) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var currency by remember { mutableStateOf(initial?.currency ?: "USD") }
    
    val formattedInitialAmount = remember(initial) {
        initial?.amount?.let { 
            if (initial.currency == "P") "%.2f".format(it) else "%,.2f".format(Locale.US, it)
        } ?: if (currency == "P") "1.00" else ""
    }
    var amount by remember { mutableStateOf(formattedInitialAmount) }
    var isMargin by remember { mutableStateOf(initial?.isMargin ?: false) }
    var portfolioRef by remember { mutableStateOf(initial?.portfolioRef ?: "") }

    val isValidAmount = remember(amount) {
        amount.isEmpty() || amount == "-" || amount.replace(",", "").toDoubleOrNull() != null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Cash Entry" else "Edit Entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = currency,
                    onValueChange = { currency = it.uppercase() },
                    label = { Text("Currency (ISO or 'P')") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (currency == "P") {
                    OutlinedTextField(
                        value = portfolioRef,
                        onValueChange = { portfolioRef = it.lowercase() },
                        label = { Text("Portfolio Reference (Slug)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { input -> 
                        if (input.all { it.isDigit() || it == '.' || it == ',' || it == '-' }) {
                            amount = input
                        }
                    },
                    label = { Text(if (currency == "P") "Multiplier" else "Amount (negative = loan)") },
                    placeholder = { Text(if (currency == "P") "1.00" else "0.00") },
                    singleLine = true,
                    isError = !isValidAmount,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(isMargin, { isMargin = it })
                    Text("Margin / Loan", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedAmount = amount.replace(",", "").toDoubleOrNull() ?: (if (currency == "P") 1.0 else 0.0)
                    val entry = CashEntry(
                        id = initial?.id ?: 0,
                        label = label.trim(),
                        currency = currency.trim().uppercase(),
                        amount = parsedAmount,
                        isMargin = isMargin,
                        portfolioRef = if (currency == "P") portfolioRef.trim() else null
                    )
                    if (entry.label.isNotEmpty() && isValidAmount) onSave(entry)
                },
                enabled = label.isNotBlank() && isValidAmount && (currency != "P" || portfolioRef.isNotBlank())
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
