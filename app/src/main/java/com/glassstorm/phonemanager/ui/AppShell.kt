package com.glassstorm.phonemanager.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.ui.components.Scaffold

const val GoRouteDashboard = "dashboard"
const val GoRoutePairing = "pairing"
const val GoRouteStream = "stream"
const val GoRouteDevices = "devices"
const val GoRouteSettings = "settings"

val GO_ROUTES: List<String> =
    listOf(
        GoRouteDashboard,
        GoRoutePairing,
        GoRouteStream,
        GoRouteDevices,
        GoRouteSettings,
    )

/**
 * The app shell: one [NavHost] over the five routes the hub exposes.
 *
 * [GoContext] is the composition root's registry, handed down so each screen can
 * build its ViewModel against the domain ports. The default is an EMPTY registry
 * on purpose: the shell must still render (screens degrade to an "unavailable"
 * line) when a port is missing, which is exactly the state the shell test composes.
 */
@Composable
fun AppShell(
    GoStartRoute: String = GoRouteDashboard,
    GoContext: Context = Context(),
) {
    val GoNavController = rememberNavController()

    Scaffold(modifier = Modifier.fillMaxSize()) { GoInsets ->
        NavHost(
            navController = GoNavController,
            startDestination = GoStartRoute,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(GoRouteDashboard) { DashboardScreen(GoContext, Modifier.padding(GoInsets)) }
            composable(GoRoutePairing) { PairingScreen(GoContext, Modifier.padding(GoInsets)) }
            composable(GoRouteStream) { StreamScreen(GoContext, Modifier.padding(GoInsets)) }
            composable(GoRouteDevices) { DevicesScreen(GoContext, Modifier.padding(GoInsets)) }
            composable(GoRouteSettings) { SettingsScreen(GoContext, Modifier.padding(GoInsets)) }
        }
    }
}
