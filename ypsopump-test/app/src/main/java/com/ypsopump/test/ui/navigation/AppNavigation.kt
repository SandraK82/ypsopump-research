package com.ypsopump.test.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ypsopump.test.key.SecureKeyStore
import com.ypsopump.test.ui.screens.*

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Connection : Screen("connection", "Connect", Icons.Default.Bluetooth)
    data object Key : Screen("key", "Key", Icons.Default.Key)
    data object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Dashboard)
    data object Commands : Screen("commands", "Commands", Icons.Default.Terminal)
    data object History : Screen("history", "History", Icons.Default.History)
    data object Bolus : Screen("bolus", "Bolus", Icons.Default.Vaccines)
    data object BleLog : Screen("blelog", "BLE Log", Icons.Default.Code)
}

val bottomNavScreens = listOf(
    Screen.Connection,
    Screen.Key,
    Screen.Dashboard,
    Screen.Commands,
    Screen.History,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(keyStore: SecureKeyStore) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("YpsoPump Test") },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.Bolus.route) }) {
                        Icon(Icons.Default.Vaccines, "Bolus")
                    }
                    IconButton(onClick = { navController.navigate(Screen.BleLog.route) }) {
                        Icon(Icons.Default.Code, "BLE Log")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                bottomNavScreens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
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
            startDestination = Screen.Connection.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Connection.route) { ConnectionScreen() }
            composable(Screen.Key.route) { KeyScreen(keyStore) }
            composable(Screen.Dashboard.route) { DashboardScreen() }
            composable(Screen.Commands.route) { CommandScreen() }
            composable(Screen.History.route) { HistoryScreen() }
            composable(Screen.Bolus.route) { BolusScreen() }
            composable(Screen.BleLog.route) { BleLogScreen() }
        }
    }
}
