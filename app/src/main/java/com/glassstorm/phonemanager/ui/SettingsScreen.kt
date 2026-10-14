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
import com.glassstorm.phonemanager.core.model.HotspotMode
import com.glassstorm.phonemanager.ui.components.Button
import com.glassstorm.phonemanager.ui.components.ButtonVariant
import com.glassstorm.phonemanager.ui.components.Text
import com.glassstorm.phonemanager.ui.components.card.Card

/**
 * Hub configuration: the hotspot mode, the Doze exemption, and the protocol/port
 * the hub exposes.
 *
 * Every setting is read and written through the `AppConfig` domain port, so this
 * screen never names the adapter that persists them. Speech-to-text is not
 * configurable: the hub runs a single on-device engine, so there is no engine
 * selector and no cloud credential field.
 */
@Composable
fun SettingsScreen(
    viewModelFactory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Settings", style = AppTheme.typography.h2)

        if (!state.configAvailable) {
            Text(text = "Configuration not available")
            return@Column
        }

        hotspotCard(state, viewModel)

        batteryCard(state, viewModel)

        endpointCard(state)
    }
}

@Composable
private fun hotspotCard(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Hotspot", style = AppTheme.typography.h4)
            Text(text = "Hotspot mode: ${state.hotspotMode}")

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    text = "Hotspot mode Manual",
                    variant = ButtonVariant.SecondaryOutlined,
                    onClick = { viewModel.onSelectHotspotMode(HotspotMode.MANUAL) },
                )
                Button(
                    text = "Hotspot mode Auto",
                    variant = ButtonVariant.SecondaryOutlined,
                    onClick = { viewModel.onSelectHotspotMode(HotspotMode.AUTO) },
                )
            }
        }
    }
}

@Composable
private fun batteryCard(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Battery", style = AppTheme.typography.h4)

            if (!state.batteryAvailable) {
                Text(text = "Battery exemption not available")
                return@Column
            }

            Text(
                text = "Battery optimization: ${if (state.batteryExempt) "exempt" else "not exempt"}",
            )
            Button(
                text = "Request battery exemption",
                enabled = !state.batteryExempt,
                onClick = viewModel::onRequestBatteryExemption,
            )
        }
    }
}

@Composable
private fun endpointCard(state: SettingsUiState) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Endpoint", style = AppTheme.typography.h4)
            Text(text = "Protocol: ${state.protocol}")
            Text(text = "Hub port: ${state.hubPort}")
        }
    }
}
