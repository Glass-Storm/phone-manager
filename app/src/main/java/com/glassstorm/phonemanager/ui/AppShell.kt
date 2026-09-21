package com.glassstorm.phonemanager.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.glassstorm.phonemanager.core.domain.context.Context
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
 * [context] is the composition root's registry, handed down so each screen can
 * build its ViewModel against the domain ports. The default is an EMPTY registry
 * on purpose: the shell must still render (screens degrade to an "unavailable"
 * line) when a port is missing, which is exactly the state the shell test composes.
 */
@Composable
fun AppShell(
    startRoute: String = ROUTE_DASHBOARD,
    context: Context = Context(),
) {
    val navController = rememberNavController()

    Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(ROUTE_DASHBOARD) { DashboardScreen(context, Modifier.padding(insets)) }
            composable(ROUTE_PAIRING) { PairingScreen(context, Modifier.padding(insets)) }
            composable(ROUTE_STREAM) { StreamScreen(context, Modifier.padding(insets)) }
            composable(ROUTE_DEVICES) { DevicesScreen(context, Modifier.padding(insets)) }
            composable(ROUTE_SETTINGS) { SettingsScreen(context, Modifier.padding(insets)) }
        }
    }
}
