package com.glassstorm.phonemanager.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import com.glassstorm.phonemanager.ui.hub.HubMotion

const val ROUTE_DASHBOARD = "dashboard"
const val ROUTE_STREAM = "stream"
const val ROUTE_SETTINGS = "settings"

val ROUTES: List<String> =
    listOf(
        ROUTE_DASHBOARD,
        ROUTE_STREAM,
        ROUTE_SETTINGS,
    )

/**
 * The app shell: one [NavHost] over the three routes the hub exposes.
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
            HubNavigationBar(
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
            composable(ROUTE_STREAM) { StreamScreen(viewModelFactory, Modifier.padding(insets)) }
            composable(ROUTE_SETTINGS) { SettingsScreen(viewModelFactory, Modifier.padding(insets)) }
        }
    }
}

/**
 * A one-control-per-route bottom bar, so every destination is reachable from any
 * other. Each item pairs an icon with its label; the selected route uses `primary`
 * for both plus a `primary` indicator bar, so selection never relies on colour
 * alone (the label is always present). The bar is padded for the system navigation
 * inset so the last row of controls is never under the system chrome.
 */
@Composable
private fun HubNavigationBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
        color = AppTheme.colors.surface,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            ROUTES.forEach { route ->
                NavigationItem(
                    route = route,
                    selected = route == currentRoute,
                    onClick = { onNavigate(route) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavigationItem(
    route: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) AppTheme.colors.primary else AppTheme.colors.textSecondary,
        animationSpec = tween(durationMillis = HubMotion.tweenMillis(), easing = FastOutSlowInEasing),
        label = "navigationItemTint",
    )

    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = HubNavigationDefaults.itemMinHeight),
        color = AppTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = HubNavigationDefaults.itemVerticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HubNavigationDefaults.itemGap),
        ) {
            Box(
                modifier =
                    Modifier
                        .width(HubNavigationDefaults.indicatorWidth)
                        .height(HubNavigationDefaults.indicatorHeight)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) AppTheme.colors.primary else AppTheme.colors.transparent),
            )
            Image(
                imageVector = routeIcon(route),
                contentDescription = null,
                modifier = Modifier.size(HubNavigationDefaults.iconSize),
                colorFilter = ColorFilter.tint(tint),
            )
            Text(
                text = routeLabel(route),
                style = AppTheme.typography.label2,
                color = tint,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private object HubNavigationDefaults {
    val itemMinHeight: Dp = 56.dp
    val itemVerticalPadding: Dp = 8.dp
    val itemGap: Dp = 4.dp
    val indicatorWidth: Dp = 28.dp
    val indicatorHeight: Dp = 3.dp
    val iconSize: Dp = 24.dp
}

private fun routeLabel(route: String): String =
    when (route) {
        ROUTE_DASHBOARD -> "總覽"
        ROUTE_STREAM -> "即時"
        ROUTE_SETTINGS -> "設定"
        else -> route
    }

private fun routeIcon(route: String): ImageVector =
    when (route) {
        ROUTE_DASHBOARD -> Icons.Default.Home
        ROUTE_STREAM -> Icons.Default.PlayArrow
        ROUTE_SETTINGS -> Icons.Default.Settings
        else -> Icons.Default.Home
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
