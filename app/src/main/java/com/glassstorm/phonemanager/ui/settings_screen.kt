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
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.dto.HotspotMode
import com.glassstorm.phonemanager.domain.dto.SttEngine
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
    GoContext: Context,
    modifier: Modifier = Modifier,
) {
    val GoViewModel: SettingsViewModel = viewModel { SettingsViewModel(GoContext) }
    val GoState by GoViewModel.GoUiState.collectAsState()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Settings", style = AppTheme.typography.h2)

        if (!GoState.GoConfigAvailable) {
            Text(text = "Configuration not available")
            return@Column
        }

        GoSpeechCard(GoState, GoViewModel)

        GoHotspotCard(GoState, GoViewModel)

        GoBatteryCard(GoState, GoViewModel)

        GoEndpointCard(GoState)
    }
}

@Composable
private fun GoSpeechCard(GoState: SettingsUiState, GoViewModel: SettingsViewModel) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Speech engine", style = AppTheme.typography.h4)
            Text(text = "Current engine: ${GoState.GoSttEngine}")

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    text = "Use Mock",
                    variant = ButtonVariant.SecondaryOutlined,
                    onClick = { GoViewModel.GoOnSelectEngine(SttEngine.MOCK) },
                )
                Button(
                    text = "Use Speechmatics",
                    variant = ButtonVariant.SecondaryOutlined,
                    onClick = { GoViewModel.GoOnSelectEngine(SttEngine.SPEECHMATICS) },
                )
            }

            Text(text = "API key configured: ${if (GoState.GoApiKeyConfigured) "yes" else "no"}")

            OutlinedTextField(
                value = GoState.GoApiKeyDraft,
                onValueChange = GoViewModel::GoOnApiKeyDraftChanged,
                singleLine = true,
                visualTransformation =
                    if (GoState.GoApiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    text = "Set API key",
                    variant = ButtonVariant.PrimaryOutlined,
                    onClick = GoViewModel::GoOnSaveApiKey,
                )
                Button(
                    text = if (GoState.GoApiKeyVisible) "Hide API key" else "Show API key",
                    variant = ButtonVariant.SecondaryOutlined,
                    onClick = GoViewModel::GoOnToggleApiKeyVisibility,
                )
            }

            Text(text = "Current region: ${GoState.GoRegion}")

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GoRegionChoices.forEach { GoRegionValue ->
                    Button(
                        text = "Region $GoRegionValue",
                        variant = ButtonVariant.SecondaryOutlined,
                        onClick = { GoViewModel.GoOnSelectRegion(GoRegionValue) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GoHotspotCard(GoState: SettingsUiState, GoViewModel: SettingsViewModel) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Hotspot", style = AppTheme.typography.h4)
            Text(text = "Hotspot mode: ${GoState.GoHotspotMode}")

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    text = "Hotspot mode Manual",
                    variant = ButtonVariant.SecondaryOutlined,
                    onClick = { GoViewModel.GoOnSelectHotspotMode(HotspotMode.MANUAL) },
                )
                Button(
                    text = "Hotspot mode Auto",
                    variant = ButtonVariant.SecondaryOutlined,
                    onClick = { GoViewModel.GoOnSelectHotspotMode(HotspotMode.AUTO) },
                )
            }
        }
    }
}

@Composable
private fun GoBatteryCard(GoState: SettingsUiState, GoViewModel: SettingsViewModel) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Battery", style = AppTheme.typography.h4)

            if (!GoState.GoBatteryAvailable) {
                Text(text = "Battery exemption not available")
                return@Column
            }

            Text(
                text = "Battery optimization: ${if (GoState.GoBatteryExempt) "exempt" else "not exempt"}",
            )
            Button(
                text = "Request battery exemption",
                enabled = !GoState.GoBatteryExempt,
                onClick = GoViewModel::GoOnRequestBatteryExemption,
            )
        }
    }
}

@Composable
private fun GoEndpointCard(GoState: SettingsUiState) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Endpoint", style = AppTheme.typography.h4)
            Text(text = "Protocol: ${GoState.GoProtocol}")
            Text(text = "Hub port: ${GoState.GoHubPort}")
        }
    }
}

private val GoRegionChoices = listOf("global", "eu", "us", "au")
