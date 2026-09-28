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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glassstorm.phonemanager.ui.components.Button
import com.glassstorm.phonemanager.ui.components.ButtonVariant
import com.glassstorm.phonemanager.ui.components.HorizontalDivider
import com.glassstorm.phonemanager.ui.components.Text
import com.glassstorm.phonemanager.ui.components.card.Card

/**
 * Ecosystem hub status: listener state, hotspot credentials, bound port and how
 * many devices are paired.
 *
 * [viewModelFactory] is the Dagger-backed factory the shell threads down: the
 * ViewModel and its ports are resolved from the compile-time graph, so a wiring
 * error is a build failure rather than an "unavailable" state. A port that is
 * present but FAILING still renders as "not available" instead of crashing.
 */
@Composable
fun DashboardScreen(
    viewModelFactory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
) {
    val viewModel: DashboardViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Dashboard", style = AppTheme.typography.h2)

        hubCard(state, viewModel)

        hotspotCard(state, viewModel)
    }
}

@Composable
private fun hubCard(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
) {
    Card(modifier = Modifier.padding(vertical = 2.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Hub", style = AppTheme.typography.h4)

            if (!state.hubAvailable) {
                Text(text = "Hub server not available")
                return@Column
            }

            Text(text = if (state.running) "Hub: running" else "Hub: stopped")
            Text(text = "Port: ${state.boundPort}")
            Text(text = "Paired devices: ${state.pairedCount}")

            state.startError?.let { Text(text = it) }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    text = "Start hub",
                    enabled = !state.running,
                    onClick = viewModel::onStartHub,
                )
                Button(
                    text = "Stop hub",
                    variant = ButtonVariant.SecondaryOutlined,
                    enabled = state.running,
                    onClick = viewModel::onStopHub,
                )
            }
        }
    }
}

@Composable
private fun hotspotCard(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
) {
    Card(modifier = Modifier.padding(vertical = 2.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Hotspot", style = AppTheme.typography.h4)

            if (!state.hotspotAvailable) {
                Text(text = "Hotspot not available")
                return@Column
            }

            state.hotspotError?.let { Text(text = it) }

            state.hotspot?.let { info ->
                Text(text = "SSID: ${info.ssid}")
                Text(text = "Passphrase: ${info.passphrase}")
                Text(text = "Gateway: ${info.gatewayIp}")
            }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    text = "Start hotspot",
                    enabled = state.hotspot == null,
                    onClick = viewModel::onStartHotspot,
                )
                Button(
                    text = "Stop hotspot",
                    variant = ButtonVariant.SecondaryOutlined,
                    enabled = state.hotspot != null,
                    onClick = viewModel::onStopHotspot,
                )
            }
        }
    }
}
