package com.ibviewer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ibviewer.MainViewModel
import com.ibviewer.data.model.MarginAlertSettings
import com.ibviewer.ui.theme.ext

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ext     = MaterialTheme.ext
    val current by vm.marginAlertSettings.collectAsState()

    var enabled  by remember(current) { mutableStateOf(current.enabled) }
    var lowerPct by remember(current) { mutableStateOf(current.lowerPct.toString()) }
    var upperPct by remember(current) { mutableStateOf(current.upperPct.toString()) }
    var interval by remember(current) { mutableStateOf(current.checkIntervalMinutes.toString()) }

    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ext.bgPrimary)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = ext.textPrimary)

        // ── Margin Alert section ──────────────────────────────────────────────
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
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
                    Switch(enabled, { enabled = it })
                }

                if (enabled) {
                    OutlinedTextField(
                        value        = lowerPct,
                        onValueChange = { lowerPct = it },
                        label        = { Text("Lower threshold (%)") },
                        supportingText = { Text("Alert when margin drops below this", fontSize = 11.sp) },
                        singleLine   = true,
                        modifier     = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value        = upperPct,
                        onValueChange = { upperPct = it },
                        label        = { Text("Upper threshold (%)") },
                        supportingText = { Text("Alert when margin rises above this", fontSize = 11.sp) },
                        singleLine   = true,
                        modifier     = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value        = interval,
                        onValueChange = { interval = it },
                        label        = { Text("Check interval (minutes)") },
                        supportingText = { Text("Minimum 15 min recommended", fontSize = 11.sp) },
                        singleLine   = true,
                        modifier     = Modifier.fillMaxWidth()
                    )
                }

                Button(
                    onClick = {
                        val settings = MarginAlertSettings(
                            enabled              = enabled,
                            lowerPct             = lowerPct.toDoubleOrNull() ?: 20.0,
                            upperPct             = upperPct.toDoubleOrNull() ?: 50.0,
                            checkIntervalMinutes = interval.toIntOrNull()?.coerceAtLeast(15) ?: 15
                        )
                        vm.saveMarginAlertSettings(settings)
                        saved = true
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Save")
                }

                if (saved) {
                    Text("✓ Saved", color = ext.positive, fontSize = 12.sp)
                    LaunchedEffect(saved) {
                        kotlinx.coroutines.delay(2000)
                        saved = false
                    }
                }
            }
        }

        // ── About ─────────────────────────────────────────────────────────────
        Surface(
            shape          = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            color          = ext.bgElevated,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("About", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = ext.textPrimary)
                Text("IB Viewer 1.0", color = ext.textTertiary, fontSize = 12.sp)
                Text("Portfolio tracking & rebalancing", color = ext.textTertiary, fontSize = 12.sp)
            }
        }
    }
}
