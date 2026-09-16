package com.glassstorm.phonemanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.ui.components.Button
import com.glassstorm.phonemanager.ui.components.ButtonVariant
import com.glassstorm.phonemanager.ui.components.HorizontalDivider
import com.glassstorm.phonemanager.ui.components.Text
import com.glassstorm.phonemanager.ui.components.card.Card

/**
 * Ecosystem hub status: listener state, hotspot credentials, bound port and how
 * many devices are paired.
 *
 * The [Context] is the composition root's registry; the ViewModel is built from
 * it with `viewModel(initializer)` because this project has no DI framework.
 * A port that is not registered renders as "not available" — the app shell test
 * composes an almost-empty Context, and this screen must never crash there.
 */
@Composable
fun DashboardScreen(
    GoContext: Context,
    modifier: Modifier = Modifier,
) {
    val GoViewModel: DashboardViewModel = viewModel { DashboardViewModel(GoContext) }
    val GoState by GoViewModel.GoUiState.collectAsState()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Dashboard", style = AppTheme.typography.h2)

        GoHubCard(GoState, GoViewModel)

        GoHotspotCard(GoState, GoViewModel)
    }
}

@Composable
private fun GoHubCard(
    GoState: DashboardUiState,
    GoViewModel: DashboardViewModel,
) {
    Card(modifier = Modifier.padding(vertical = 2.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Hub", style = AppTheme.typography.h4)

            if (!GoState.GoHubAvailable) {
                Text(text = "Hub server not available")
                return@Column
            }

            Text(text = if (GoState.GoRunning) "Hub: running" else "Hub: stopped")
            Text(text = "Port: ${GoState.GoBoundPort}")
            Text(text = "Paired devices: ${GoState.GoPairedCount}")

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    text = "Start hub",
                    enabled = !GoState.GoRunning,
                    onClick = GoViewModel::GoOnStartHub,
                )
                Button(
                    text = "Stop hub",
                    variant = ButtonVariant.SecondaryOutlined,
                    enabled = GoState.GoRunning,
                    onClick = GoViewModel::GoOnStopHub,
                )
            }
        }
    }
}

@Composable
private fun GoHotspotCard(
    GoState: DashboardUiState,
    GoViewModel: DashboardViewModel,
) {
    Card(modifier = Modifier.padding(vertical = 2.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Hotspot", style = AppTheme.typography.h4)

            if (!GoState.GoHotspotAvailable) {
                Text(text = "Hotspot not available")
                return@Column
            }

            GoState.GoHotspotError?.let { Text(text = it) }

            GoState.GoHotspot?.let { GoInfo ->
                Text(text = "SSID: ${GoInfo.GoSsid}")
                Text(text = "Passphrase: ${GoInfo.GoPassphrase}")
                Text(text = "Gateway: ${GoInfo.GoGatewayIp}")
            }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    text = "Start hotspot",
                    enabled = GoState.GoHotspot == null,
                    onClick = GoViewModel::GoOnStartHotspot,
                )
                Button(
                    text = "Stop hotspot",
                    variant = ButtonVariant.SecondaryOutlined,
                    enabled = GoState.GoHotspot != null,
                    onClick = GoViewModel::GoOnStopHotspot,
                )
            }
        }
    }
}
