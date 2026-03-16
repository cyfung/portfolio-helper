package com.portfoliohelper

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.portfoliohelper.ui.components.DynamicCurrencySwitcher
import com.portfoliohelper.ui.screens.CashScreen
import com.portfoliohelper.ui.screens.GroupsScreen
import com.portfoliohelper.ui.screens.PortfolioScreen
import com.portfoliohelper.ui.screens.SettingsScreen
import com.portfoliohelper.ui.theme.PortfolioHelperTheme
import com.portfoliohelper.ui.theme.ext
import kotlinx.serialization.Serializable

@Serializable object PortfolioRoute
@Serializable object GroupsRoute
@Serializable object CashRoute
@Serializable object SettingsRoute

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate started")
        enableEdgeToEdge()
        
        setContent {
            PortfolioHelperTheme {
                PortfolioHelperApp(vm)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioHelperApp(vm: MainViewModel) {
    val navController = rememberNavController()
    val ext = MaterialTheme.ext
    
    val selectedCurrency by vm.displayCurrency.collectAsState()
    val cashEntries by vm.cashEntries.collectAsState()
    
    // Dynamic list taking currency from Cash, with USD always first
    val currencies = (listOf("USD") + cashEntries.map { it.currency }).distinct()

    Scaffold(
        topBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            val currentItem = navItems.find { item ->
                currentDestination?.hierarchy?.any { 
                    it.route?.contains(item.route::class.simpleName ?: "") == true 
                } == true
            }
            TopAppBar(
                title = { 
                    Text(
                        text = currentItem?.label ?: "Portfolio Helper",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    ) 
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
            composable<SettingsRoute> { SettingsScreen(vm) }
        }
    }
}
