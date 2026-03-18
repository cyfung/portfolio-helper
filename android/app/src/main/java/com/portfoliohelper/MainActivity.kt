package com.portfoliohelper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.portfoliohelper.data.model.Portfolio
import com.portfoliohelper.data.repository.YahooQuote
import com.portfoliohelper.ui.components.DynamicCurrencySwitcher
import com.portfoliohelper.ui.components.UpdateTimestamp
import com.portfoliohelper.ui.screens.CashScreen
import com.portfoliohelper.ui.screens.GroupsScreen
import com.portfoliohelper.ui.screens.PortfolioScreen
import com.portfoliohelper.ui.screens.SettingsScreen
import com.portfoliohelper.ui.theme.PortfolioHelperTheme
import com.portfoliohelper.ui.theme.ext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object PortfolioRoute
@Serializable object GroupsRoute
@Serializable object CashRoute
@Serializable object SettingsRoute

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.i("MainActivity", "Notification permission granted")
        } else {
            Log.w("MainActivity", "Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate started")
        enableEdgeToEdge()

        // Request permission only if alerts are enabled AND notifications are turned on
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.isAnyAlertEnabled, vm.marginCheckNotificationsEnabled) { alertsEnabled, notifsOn ->
                    alertsEnabled && notifsOn
                }.collect { shouldAsk ->
                    if (shouldAsk) {
                        askNotificationPermission()
                    }
                }
            }
        }

        setContent {
            PortfolioHelperTheme {
                PortfolioHelperApp(vm, onAskPermission = { askNotificationPermission() })
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.i("MainActivity", "Requesting notification permission")
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

data class NavItem<T : Any>(val route: T, val label: String, val icon: ImageVector)

val navItems = listOf(
    NavItem(PortfolioRoute, "Portfolio", Icons.Default.ShowChart),
    NavItem(GroupsRoute, "Groups", Icons.Default.AccountTree),
    NavItem(CashRoute, "Cash", Icons.Default.AccountBalance),
    NavItem(SettingsRoute, "Settings", Icons.Default.Settings)
)

@Composable
fun PortfolioSelectorTitle(
    portfolios: List<Portfolio>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    marketData: Map<String, YahooQuote>,
    relatedSymbols: Set<String>
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = portfolios.find { it.serialId == selectedId } ?: portfolios.firstOrNull()

    Box {
        Row(
            modifier = Modifier
                .clickable { expanded = true }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selected?.displayName ?: selectedId.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            UpdateTimestamp(marketData, relatedSymbols)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            portfolios.filter { it.serialId != selected?.serialId }.forEach { portfolio ->
                DropdownMenuItem(
                    text = { Text(portfolio.displayName) },
                    onClick = {
                        onSelect(portfolio.serialId)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioHelperApp(vm: MainViewModel, onAskPermission: () -> Unit) {
    val navController = rememberNavController()
    val ext = MaterialTheme.ext

    val selectedCurrency by vm.displayCurrency.collectAsState()
    val allCashEntries by vm.allCashEntries.collectAsState()
    val portfolios by vm.portfolios.collectAsState()
    val selectedPortfolioId by vm.selectedPortfolioId.collectAsState()
    val marketData by vm.marketData.collectAsState()
    val activeSymbols by vm.activeSymbols.collectAsState()

    // Dynamic list taking currency from Cash across all portfolios, with USD always first
    val currencies = (listOf("USD") + allCashEntries.map { it.currency }).distinct()

    Scaffold(
        topBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            val currentItem = navItems.find { item ->
                currentDestination?.hierarchy?.any {
                    it.route?.contains(item.route::class.simpleName ?: "") == true
                } == true
            }
            val isSettingsScreen = currentItem?.route is SettingsRoute

            TopAppBar(
                title = {
                    if (!isSettingsScreen && portfolios.size > 1) {
                        PortfolioSelectorTitle(
                            portfolios = portfolios,
                            selectedId = selectedPortfolioId,
                            onSelect = vm::selectPortfolio,
                            marketData = marketData,
                            relatedSymbols = activeSymbols
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Text(
                                text = currentItem?.label ?: "Portfolio Helper",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            if (!isSettingsScreen) {
                                Spacer(Modifier.width(8.dp))
                                UpdateTimestamp(marketData, activeSymbols)
                            }
                        }
                    }
                },
                actions = {
                    DynamicCurrencySwitcher(
                        currencies = currencies,
                        selected = selectedCurrency,
                        onCurrencySelected = { vm.saveDisplayCurrency(it) }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ext.bgPrimary,
                    titleContentColor = ext.textPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = ext.bgPrimary,
                contentColor = ext.textPrimary
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                navItems.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        selected = currentDestination?.hierarchy?.any {
                            it.route?.contains(item.route::class.simpleName ?: "") == true
                        } == true,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = PortfolioRoute,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable<PortfolioRoute> { PortfolioScreen(vm) }
            composable<GroupsRoute> { GroupsScreen(vm) }
            composable<CashRoute> { CashScreen(vm) }
            composable<SettingsRoute> { SettingsScreen(vm, onAskPermission = onAskPermission) }
        }
    }
}
