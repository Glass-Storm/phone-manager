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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glassstorm.phonemanager.core.domain.context.Context
import com.glassstorm.phonemanager.core.model.HotspotMode
import com.glassstorm.phonemanager.core.model.SttEngine
import com.glassstorm.phonemanager.ui.components.Button
import com.glassstorm.phonemanager.ui.components.ButtonVariant
import com.glassstorm.phonemanager.ui.components.Text
import com.glassstorm.phonemanager.ui.components.card.Card
import com.glassstorm.phonemanager.ui.components.textfield.OutlinedTextField

/**
 * Hub configuration: the speech engine and its cloud credentials, the hotspot mode,
 * the Doze exemption, and the protocol/port the hub exposes.
 *
 * Every setting is read and written through the `AppConfig` domain port, so this
 * screen never names the adapter that persists them. The API-key field mirrors the
 * reference client: the stored key is present for editing but rendered masked until
 * the user explicitly reveals it.
 */
@Composable
fun SettingsScreen(
    context: Context,
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = viewModel { SettingsViewModel(context) }
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

        speechCard(state, viewModel)

        hotspotCard(state, viewModel)

        batteryCard(state, viewModel)

        endpointCard(state)
    }
}

@Composable
private fun speechCard(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Speech engine", style = AppTheme.typography.h4)
            Text(text = "Current engine: ${state.sttEngine}")

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    text = "Use Mock",
                    variant = ButtonVariant.SecondaryOutlined,
                    onClick = { viewModel.onSelectEngine(SttEngine.MOCK) },
                )
                Button(
                    text = "Use Speechmatics",
                    variant = ButtonVariant.SecondaryOutlined,
                    onClick = { viewModel.onSelectEngine(SttEngine.SPEECHMATICS) },
                )
            }

            Text(text = "API key configured: ${if (state.apiKeyConfigured) "yes" else "no"}")

            OutlinedTextField(
                value = state.apiKeyDraft,
                onValueChange = viewModel::onApiKeyDraftChanged,
                singleLine = true,
                visualTransformation =
                    if (state.apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    text = "Set API key",
                    variant = ButtonVariant.PrimaryOutlined,
                    onClick = viewModel::onSaveApiKey,
                )
                Button(
                    text = if (state.apiKeyVisible) "Hide API key" else "Show API key",
                    variant = ButtonVariant.SecondaryOutlined,
                    onClick = viewModel::onToggleApiKeyVisibility,
                )
            }

            Text(text = "Current region: ${state.region}")

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                regionChoices.forEach { regionValue ->
                    Button(
                        text = "Region $regionValue",
                        variant = ButtonVariant.SecondaryOutlined,
                        onClick = { viewModel.onSelectRegion(regionValue) },
                    )
                }
            }
        }
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

private val regionChoices = listOf("global", "eu", "us", "au")
