package com.glassstorm.phonemanager.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.glassstorm.phonemanager.ui.components.Scaffold

const val ROUTE_DASHBOARD = "dashboard"
const val ROUTE_PAIRING = "pairing"
const val ROUTE_STREAM = "stream"
const val ROUTE_DEVICES = "devices"
const val ROUTE_SETTINGS = "settings"

val ROUTES: List<String> =
    listOf(
        ROUTE_DASHBOARD,
        ROUTE_PAIRING,
        ROUTE_STREAM,
        ROUTE_DEVICES,
        ROUTE_SETTINGS,
    )

/**
 * The app shell: one [NavHost] over the five routes the hub exposes.
 *
 * [viewModelFactory] is the Dagger-backed [ViewModelProvider.Factory] the
 * composition root owns, handed down so each screen can resolve its ViewModel and
 * its domain ports from the compile-time graph.
 */
@Composable
fun AppShell(
    viewModelFactory: ViewModelProvider.Factory,
    startRoute: String = ROUTE_DASHBOARD,
) {
    val navController = rememberNavController()

    Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(ROUTE_DASHBOARD) { DashboardScreen(viewModelFactory, Modifier.padding(insets)) }
            composable(ROUTE_PAIRING) { PairingScreen(viewModelFactory, Modifier.padding(insets)) }
            composable(ROUTE_STREAM) { StreamScreen(viewModelFactory, Modifier.padding(insets)) }
            composable(ROUTE_DEVICES) { DevicesScreen(viewModelFactory, Modifier.padding(insets)) }
            composable(ROUTE_SETTINGS) { SettingsScreen(viewModelFactory, Modifier.padding(insets)) }
        }
    }
}
