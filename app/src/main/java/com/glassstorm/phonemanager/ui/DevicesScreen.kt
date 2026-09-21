package com.glassstorm.phonemanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.glassstorm.phonemanager.core.model.Device
import com.glassstorm.phonemanager.ui.components.Button
import com.glassstorm.phonemanager.ui.components.ButtonVariant
import com.glassstorm.phonemanager.ui.components.HorizontalDivider
import com.glassstorm.phonemanager.ui.components.Text
import com.glassstorm.phonemanager.ui.components.card.Card
import java.time.Instant

/**
 * Paired-device management: every device that has completed pairing, its role, and
 * when it was last seen. Revoking removes the device, which also invalidates its
 * token (the repository row holding the token hash is what the token verifies
 * against).
 *
 * The list comes from the `DeviceRepository` domain port; the screen never names an
 * adapter. A failing store renders "not available" rather than crashing.
 */
@Composable
fun DevicesScreen(
    viewModelFactory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
) {
    val viewModel: DevicesViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Devices", style = AppTheme.typography.h2)

        if (!state.available) {
            Text(text = "Device store not available")
            return@Column
        }

        deviceListCard(state, viewModel)
    }
}

@Composable
private fun deviceListCard(
    state: DevicesUiState,
    viewModel: DevicesViewModel,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Paired devices", style = AppTheme.typography.h4)

            if (state.devices.isEmpty()) {
                Text(text = "No paired devices")
                return@Column
            }

            state.devices.forEach { device ->
                Text(text = device.deviceName)
                Text(
                    text = "Role: ${device.role}",
                    style = AppTheme.typography.label2,
                )
                Text(
                    text = "Last seen: ${device.lastSeenLabel()}",
                    style = AppTheme.typography.label2,
                )
                Button(
                    text = "Revoke ${device.deviceName}",
                    variant = ButtonVariant.DestructiveOutlined,
                    onClick = { viewModel.onRevoke(device.deviceId) },
                )
                HorizontalDivider()
            }
        }
    }
}

/** A device seen after pairing: the instant. A device never seen: `never`. */
private fun Device.lastSeenLabel(): String = lastSeenMs?.let { Instant.ofEpochMilli(it).toString() } ?: "never"
