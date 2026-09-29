package com.glassstorm.phonemanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.glassstorm.phonemanager.ui.components.Scaffold
import com.glassstorm.phonemanager.ui.components.Surface
import com.glassstorm.phonemanager.ui.components.Text

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
 *
 * [navController] defaults to a remembered controller; a test passes its own so it
 * can assert the back stack directly.
 */
@Composable
fun AppShell(
    viewModelFactory: ViewModelProvider.Factory,
    startRoute: String = ROUTE_DASHBOARD,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: startRoute

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                currentRoute = currentRoute,
                onNavigate = { route -> navController.navigateToRoute(route, startRoute) },
            )
        },
    ) { insets ->
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

/**
 * A one-control-per-route bottom bar, so every destination is reachable from
 * any other. The current route is highlighted with the primary surface color.
 *
 * The labels are UPPERCASE on purpose: they are distinct from each screen's own
 * title, so a test or a user never confuses the control with the content.
 */
@Composable
private fun NavigationBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
) {
    Surface(color = AppTheme.colors.surface) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ROUTES.forEach { route ->
                val selected = route == currentRoute
                Surface(
                    selected = selected,
                    onClick = { onNavigate(route) },
                    modifier = Modifier.weight(1f),
                    color = if (selected) AppTheme.colors.primary else AppTheme.colors.surface,
                    contentColor =
                        if (selected) {
                            AppTheme.colors.onPrimary
                        } else {
                            AppTheme.colors.onSurface
                        },
                ) {
                    Text(
                        text = routeLabel(route),
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun routeLabel(route: String): String =
    when (route) {
        ROUTE_DASHBOARD -> "DASHBOARD"
        ROUTE_PAIRING -> "PAIRING"
        ROUTE_STREAM -> "STREAM"
        ROUTE_DEVICES -> "DEVICES"
        ROUTE_SETTINGS -> "SETTINGS"
        else -> route
    }

/**
 * Navigate to a bottom-bar [route] as a tab switch, not a stack push.
 *
 * `launchSingleTop` reuses the current entry, `popUpTo` keeps the stack at the
 * start destination and `saveState`/`restoreState` preserve each tab's state.
 * Without them a repeated tap stacks duplicate destinations and system-back needs
 * one pop per tap.
 */
private fun NavHostController.navigateToRoute(
    route: String,
    startRoute: String,
) {
    if (route == currentDestination?.route) return
    navigate(route) {
        popUpTo(startRoute) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
