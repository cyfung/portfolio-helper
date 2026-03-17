package com.portfoliohelper.ui.screens

import android.net.nsd.NsdServiceInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portfoliohelper.MainViewModel
import com.portfoliohelper.SyncStatus
import com.portfoliohelper.data.model.Portfolio
import com.portfoliohelper.data.model.PortfolioMarginAlert
import com.portfoliohelper.ui.theme.ext
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(vm: MainViewModel, onAskPermission: () -> Unit) {
    val ext = MaterialTheme.ext
    val syncServer by vm.syncServerInfo.collectAsState()
    val discoveredServers by vm.discoveredServers.collectAsState()
    val syncStatus by vm.syncStatus.collectAsState()
    val pnlMode by vm.pnlDisplayMode.collectAsState()
    val portfolios by vm.portfolios.collectAsState()
    val portfolioAlerts by vm.portfolioAlerts.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ext.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Data Sync Section ──────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = ext.bgElevated,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Data Sync", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = ext.textPrimary, modifier = Modifier.weight(1f))
                    if (syncServer != null) {
                        IconButton(
                            onClick = { vm.sync() },
                            enabled = syncStatus !is SyncStatus.Syncing
                        ) {
                            if (syncStatus is SyncStatus.Syncing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = ext.actionPositive)
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = "Sync Now", tint = ext.actionPositive)
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = syncStatus is SyncStatus.Error) {
                    val error = (syncStatus as? SyncStatus.Error)?.message ?: ""
                    Text(
                        text = "Error: $error",
                        color = ext.negative,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                AnimatedVisibility(visible = syncStatus is SyncStatus.Success) {
                    Text(
                        text = "✓ Sync successful",
                        color = ext.positive,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LaunchedEffect(Unit) {
                        delay(3000)
                        vm.clearSyncStatus()
                    }
                }

                if (syncServer == null) {
                    if (syncStatus is SyncStatus.Syncing) {
                        Text("Pairing and syncing...", fontSize = 12.sp, color = ext.textSecondary)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        Text("No server paired. Discovering servers on local network...", fontSize = 12.sp, color = ext.textSecondary)
                        if (discoveredServers.isEmpty()) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            discoveredServers.forEach { server ->
                                ServerItem(server) { vm.requestPairing(server) }
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Paired with: ${syncServer!!.name}", fontSize = 14.sp, color = ext.textPrimary)
                            Text("Host: ${syncServer!!.host}:${syncServer!!.port}", fontSize = 12.sp, color = ext.textTertiary)
                        }
                        TextButton(onClick = { vm.unpairServer() }) {
                            Text("Unpair", color = ext.negative)
                        }
                    }
                }
            }
        }

        // ── Pairing Dialog ───────────────────────────────────────────────────
        if (syncStatus is SyncStatus.NeedsPairing) {
            val server = (syncStatus as SyncStatus.NeedsPairing).server
            PairingPinDialog(
                serverName = server.serviceName,
                onDismiss = { vm.clearSyncStatus() },
                onConfirm = { pin -> vm.pairServer(server, pin) }
            )
        }

        // ── Portfolios & Alerts Section (Local) ───────────────────────────────────
        PortfoliosSection(
            portfolios = portfolios,
            alerts = portfolioAlerts,
            onCreatePortfolio = { vm.createPortfolio(it) },
            onRenamePortfolio = { id, name -> vm.renamePortfolio(id, name) },
            onDeletePortfolio = { vm.deletePortfolio(it) },
            onSaveAlerts = { vm.savePortfolioAlerts(it) },
            onAskPermission = onAskPermission,
            ext = ext
        )

        // ── Display Settings ───────────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = ext.bgElevated,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Display Settings", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = ext.textPrimary)

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Stock P&L Currency", modifier = Modifier.weight(1f), color = ext.textSecondary, fontSize = 13.sp)

                    TextButton(onClick = {
                        vm.savePnlDisplayMode(if (pnlMode == "DISPLAY") "NATIVE" else "DISPLAY")
                    }) {
                        Text(if (pnlMode == "DISPLAY") "Portfolio" else "Native")
                    }
                }
            }
        }

        // ── About ─────────────────────────────────────────────────────────────
        Surface(
            shape          = RoundedCornerShape(10.dp),
            color          = ext.bgElevated,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("About", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = ext.textPrimary)
                Text("Portfolio Helper 1.0", color = ext.textTertiary, fontSize = 12.sp)
                Text("Portfolio tracking & rebalancing", color = ext.textTertiary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PortfoliosSection(
    portfolios: List<Portfolio>,
    alerts: List<PortfolioMarginAlert>,
    onCreatePortfolio: (String) -> Unit,
    onRenamePortfolio: (Int, String) -> Unit,
    onDeletePortfolio: (Int) -> Unit,
    onSaveAlerts: (List<PortfolioMarginAlert>) -> Unit,
    onAskPermission: () -> Unit,
    ext: com.portfoliohelper.ui.theme.ExtendedColors
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Portfolio?>(null) }

    data class RowState(
        val id: Int,
        val name: String,
        val lowerPct: String,
        val upperPct: String
    )

    val rowStates = remember(alerts, portfolios) {
        portfolios.map { p ->
            val a = alerts.find { it.portfolioId == p.serialId }
                ?: PortfolioMarginAlert(portfolioId = p.serialId)
            val showLower = if (a.lowerPct > 0) a.lowerPct.toBigDecimal().stripTrailingZeros().toPlainString() else ""
            val showUpper = if (a.upperPct > 0) a.upperPct.toBigDecimal().stripTrailingZeros().toPlainString() else ""
            mutableStateOf(RowState(p.serialId, p.displayName, showLower, showUpper))
        }
    }

    // Debounce save for alerts and names
    LaunchedEffect(rowStates.map { it.value }) {
        delay(800)
        
        // Save alerts
        var anyEnabled = false
        val updatedAlerts = rowStates.map { stateHolder ->
            val s = stateHolder.value
            val lower = s.lowerPct.toDoubleOrNull()?.takeIf { it > 0 } ?: -1.0
            val upper = s.upperPct.toDoubleOrNull()?.takeIf { it > 0 } ?: -1.0
            val isEnabled = lower > 0 || upper > 0
            
            // Check if this specific row was just enabled
            if (isEnabled) {
                anyEnabled = true
            }

            PortfolioMarginAlert(
                portfolioId = s.id,
                lowerPct = lower,
                upperPct = upper
            )
        }
        onSaveAlerts(updatedAlerts)
        
        if (anyEnabled) {
            onAskPermission()
        }

        // Save names if changed
        rowStates.forEach { stateHolder ->
            val s = stateHolder.value
            val original = portfolios.find { it.serialId == s.id }
            if (original != null && s.name.isNotBlank() && s.name != original.displayName) {
                onRenamePortfolio(s.id, s.name.trim())
            }
        }
    }

    val minPortfolioId = remember(portfolios) {
        portfolios.minOfOrNull { it.serialId } ?: -1
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ext.bgElevated,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Portfolios & Alerts", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = ext.textPrimary)
            Text(
                "Directly edit portfolio names and margin thresholds. Changes are saved automatically.",
                fontSize = 11.sp, color = ext.textTertiary
            )

            if (portfolios.isEmpty()) {
                Text("No portfolios yet.", fontSize = 12.sp, color = ext.textTertiary)
            } else {
                // Header row
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Portfolio Name", modifier = Modifier.weight(2f), fontSize = 12.sp, color = ext.textSecondary, fontWeight = FontWeight.Medium)
                    Text("Low%", modifier = Modifier.weight(1f), fontSize = 12.sp, color = ext.textSecondary, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    Text("High%", modifier = Modifier.weight(1f), fontSize = 12.sp, color = ext.textSecondary, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.width(48.dp))
                }
                HorizontalDivider()

                rowStates.forEachIndexed { index, stateHolder ->
                    val portfolio = portfolios.getOrNull(index) ?: return@forEachIndexed
                    var state by stateHolder
                    val isDeletable = portfolios.size > 1 && portfolio.serialId != minPortfolioId

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Name
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = { state = state.copy(name = it) },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ext.actionPositive.copy(alpha = 0.5f),
                                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent
                            )
                        )
                        
                        // Low%
                        OutlinedTextField(
                            value = state.lowerPct,
                            onValueChange = { state = state.copy(lowerPct = it) },
                            placeholder = { Text("off", fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, textAlign = TextAlign.Center),
                            isError = state.lowerPct.isNotBlank() && state.lowerPct.toDoubleOrNull()?.let { it > 0 } != true
                        )
                        
                        // High%
                        OutlinedTextField(
                            value = state.upperPct,
                            onValueChange = { state = state.copy(upperPct = it) },
                            placeholder = { Text("off", fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, textAlign = TextAlign.Center),
                            isError = state.upperPct.isNotBlank() && state.upperPct.toDoubleOrNull()?.let { it > 0 } != true
                        )

                        // Delete
                        IconButton(
                            onClick = { deleteTarget = portfolio },
                            enabled = isDeletable,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = if (isDeletable) ext.negative else ext.textTertiary.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }

            TextButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.align(Alignment.Start)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Portfolio", color = ext.actionPositive)
            }
        }
    }

    if (showCreateDialog) {
        CreatePortfolioDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                onCreatePortfolio(name)
                showCreateDialog = false
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Portfolio") },
            text = { Text("Delete \"${target.displayName}\"? All positions and cash data will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePortfolio(target.serialId)
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CreatePortfolioDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Portfolio") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Portfolio name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ServerItem(server: NsdServiceInfo, onClick: () -> Unit) {
    val ext = MaterialTheme.ext
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = ext.bgElevated)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(server.serviceName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ext.textPrimary)
                Text("${server.host?.hostAddress ?: "Unknown IP"}:${server.port}", fontSize = 12.sp, color = ext.textTertiary)
            }
            Text("Tap to pair", fontSize = 11.sp, color = ext.actionPositive)
        }
    }
}

@Composable
private fun PairingPinDialog(serverName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with $serverName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the 6-digit PIN shown on the server.")
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6) pin = it },
                    label = { Text("PIN") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }, enabled = pin.length >= 4) {
                Text("Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
