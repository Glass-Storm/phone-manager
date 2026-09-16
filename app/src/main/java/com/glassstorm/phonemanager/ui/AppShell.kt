package com.glassstorm.phonemanager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.glassstorm.phonemanager.ui.components.Scaffold
import com.glassstorm.phonemanager.ui.components.Text

const val GoRouteDashboard = "dashboard"
const val GoRoutePairing = "pairing"
const val GoRouteStream = "stream"
const val GoRouteDevices = "devices"
const val GoRouteSettings = "settings"

val GO_ROUTES: List<String> = listOf(
    GoRouteDashboard,
    GoRoutePairing,
    GoRouteStream,
    GoRouteDevices,
    GoRouteSettings,
)

const val GoPlaceholderDashboard = "Dashboard"
const val GoPlaceholderPairing = "Pairing"
const val GoPlaceholderStream = "Stream"
const val GoPlaceholderDevices = "Devices"
const val GoPlaceholderSettings = "Settings"

/**
 * The app shell: one [NavHost] over the five routes the hub exposes.
 *
 * Screens are placeholders in this todo; T13/T16/T17 replace each destination body
 * with a ViewModel-backed screen while keeping these route names stable.
 */
@Composable
fun AppShell(GoStartRoute: String = GoRouteDashboard) {
    val GoNavController = rememberNavController()

    Scaffold(modifier = Modifier.fillMaxSize()) { GoInsets ->
        NavHost(
            navController = GoNavController,
            startDestination = GoStartRoute,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(GoRouteDashboard) { GoPlaceholder(GoInsets, GoPlaceholderDashboard) }
            composable(GoRoutePairing) { GoPlaceholder(GoInsets, GoPlaceholderPairing) }
            composable(GoRouteStream) { GoPlaceholder(GoInsets, GoPlaceholderStream) }
            composable(GoRouteDevices) { GoPlaceholder(GoInsets, GoPlaceholderDevices) }
            composable(GoRouteSettings) { GoPlaceholder(GoInsets, GoPlaceholderSettings) }
        }
    }
}

@Composable
private fun GoPlaceholder(GoInsets: PaddingValues, GoLabel: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(GoInsets),
        contentAlignment = Alignment.Center,
    ) {
        Text(GoLabel)
    }
}
