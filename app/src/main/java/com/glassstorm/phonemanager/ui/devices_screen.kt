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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.dto.Device
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
 * adapter. A missing or failing store renders "not available" rather than crashing.
 */
@Composable
fun DevicesScreen(
    GoContext: Context,
    modifier: Modifier = Modifier,
) {
    val GoViewModel: DevicesViewModel = viewModel { DevicesViewModel(GoContext) }
    val GoState by GoViewModel.GoUiState.collectAsState()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Devices", style = AppTheme.typography.h2)

        if (!GoState.GoAvailable) {
            Text(text = "Device store not available")
            return@Column
        }

        GoDeviceListCard(GoState, GoViewModel)
    }
}

@Composable
private fun GoDeviceListCard(GoState: DevicesUiState, GoViewModel: DevicesViewModel) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Paired devices", style = AppTheme.typography.h4)

            if (GoState.GoDevices.isEmpty()) {
                Text(text = "No paired devices")
                return@Column
            }

            GoState.GoDevices.forEach { GoDevice ->
                Text(text = GoDevice.GoDeviceName)
                Text(
                    text = "Role: ${GoDevice.GoRole}",
                    style = AppTheme.typography.label2,
                )
                Text(
                    text = "Last seen: ${GoDevice.GoLastSeenLabel()}",
                    style = AppTheme.typography.label2,
                )
                Button(
                    text = "Revoke ${GoDevice.GoDeviceName}",
                    variant = ButtonVariant.DestructiveOutlined,
                    onClick = { GoViewModel.GoOnRevoke(GoDevice.GoDeviceId) },
                )
                HorizontalDivider()
            }
        }
    }
}

/** A device seen after pairing: the instant. A device never seen: `never`. */
private fun Device.GoLastSeenLabel(): String =
    GoLastSeenMs?.let { Instant.ofEpochMilli(it).toString() } ?: "never"
