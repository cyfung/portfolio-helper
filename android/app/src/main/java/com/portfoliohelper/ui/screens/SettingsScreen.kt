package com.portfoliohelper.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.portfoliohelper.MainViewModel
import com.portfoliohelper.SyncStatus
import com.portfoliohelper.data.model.MarginAlertSettings
import com.portfoliohelper.ui.theme.ext
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val ext = MaterialTheme.ext
    val currentAlerts by vm.marginAlertSettings.collectAsState()
    val syncServer by vm.syncServerInfo.collectAsState()
    val discoveredServers by vm.discoveredServers.collectAsState()
    val syncStatus by vm.syncStatus.collectAsState()
    val pnlMode by vm.pnlDisplayMode.collectAsState()

    var enabled by remember(currentAlerts) { mutableStateOf(currentAlerts.enabled) }
    var lowerPct by remember(currentAlerts) { mutableStateOf(currentAlerts.lowerPct.toString()) }
    var upperPct by remember(currentAlerts) { mutableStateOf(currentAlerts.upperPct.toString()) }
    var interval by remember(currentAlerts) { mutableStateOf(currentAlerts.checkIntervalMinutes.toString()) }

    val saveSettings = {
        val l = lowerPct.toDoubleOrNull()
        val u = upperPct.toDoubleOrNull()
        val i = interval.toIntOrNull()
        if (l != null && u != null && i != null) {
            val settings = MarginAlertSettings(
                enabled = enabled,
                lowerPct = l,
                upperPct = u,
                checkIntervalMinutes = i.coerceAtLeast(15)
            )
            if (settings != currentAlerts) {
                vm.saveMarginAlertSettings(settings)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            saveSettings()
        } else {
            enabled = false
        }
    }

    LaunchedEffect(enabled, lowerPct, upperPct, interval) {
        delay(500) // Debounce text updates
        saveSettings()
    }

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
                        Text(if (pnlMode == "DISPLAY") "USD (Portfolio)" else "Native (Stock)")
                    }
                }
            }
        }

        // ── Margin Alert section ──────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = ext.bgElevated,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                Text("Margin Alerts",
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 15.sp,
                    color      = ext.textPrimary)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier          = Modifier.fillMaxWidth()
                ) {
                    Text("Enable background check", modifier = Modifier.weight(1f),
                        color = ext.textSecondary, fontSize = 13.sp)
                    Switch(enabled, { newValue ->
                        enabled = newValue
                        if (newValue) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        }
                    })
                }

                if (enabled) {
                    OutlinedTextField(
                        value        = lowerPct,
                        onValueChange = { lowerPct = it },
                        label        = { Text("Lower threshold (%)") },
                        supportingText = { Text("Alert when margin drops below this", fontSize = 11.sp) },
                        singleLine   =   true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier     = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value        = upperPct,
                        onValueChange = { upperPct = it },
                        label        = { Text("Upper threshold (%)") },
                        supportingText = { Text("Alert when margin rises above this", fontSize = 11.sp) },
                        singleLine   =   true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier     = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value        = interval,
                        onValueChange = { interval = it },
                        label        = { Text("Check interval (minutes)") },
                        supportingText = { Text("Minimum 15 min recommended", fontSize = 11.sp) },
                        singleLine   =   true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier     = Modifier.fillMaxWidth()
                    )
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
fun ServerItem(server: NsdServiceInfo, onPair: () -> Unit) {
    val ext = MaterialTheme.ext
    Surface(
        onClick = onPair,
        color = ext.bgSecondary,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(server.serviceName, fontWeight = FontWeight.Medium, color = ext.textPrimary)
                Text("${server.host?.hostAddress ?: "Resolving..."}:${server.port}", fontSize = 11.sp, color = ext.textSecondary)
            }
            Text("Pair", color = ext.actionPositive, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
fun PairingPinDialog(serverName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with $serverName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the 6-digit PIN displayed on your computer screen.")
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6) pin = it.filter { c -> c.isDigit() } },
                    label = { Text("PIN") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (pin.length == 6) onConfirm(pin) },
                enabled = pin.length == 6
            ) {
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
