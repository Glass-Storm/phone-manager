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
 * Pairing flow: open a single-use PIN window, show the PIN, list paired devices
 * and revoke one.
 *
 * The [Context] is the composition root's registry; the ViewModel is built from
 * it with `viewModel(initializer)` because this project has no DI framework and
 * must not gain one. An absent `PairingService` renders "not available" instead
 * of crashing the shell.
 */
@Composable
fun PairingScreen(
    GoContext: Context,
    modifier: Modifier = Modifier,
) {
    val GoViewModel: PairingViewModel = viewModel { PairingViewModel(GoContext) }
    val GoState by GoViewModel.GoUiState.collectAsState()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Pairing", style = AppTheme.typography.h2)

        if (!GoState.GoAvailable) {
            Text(text = "Pairing service not available")
            return@Column
        }

        GoWindowCard(GoState, GoViewModel)

        GoDeviceListCard(GoState, GoViewModel)
    }
}

@Composable
private fun GoWindowCard(GoState: PairingUiState, GoViewModel: PairingViewModel) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Pairing window", style = AppTheme.typography.h4)

            if (GoState.GoPin == null) {
                Text(text = "No pairing window open")
                Button(text = "Open pairing window", onClick = GoViewModel::GoOnOpenWindow)
            } else {
                Text(text = "PIN", style = AppTheme.typography.label2)
                Text(text = GoState.GoPin!!, style = AppTheme.typography.h1)
                Text(text = "Single-use. Expires in ${GoState.GoExpiresInSeconds}s.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        text = "Reopen pairing window",
                        variant = ButtonVariant.SecondaryOutlined,
                        onClick = GoViewModel::GoOnOpenWindow,
                    )
                    Button(
                        text = "Close pairing window",
                        variant = ButtonVariant.DestructiveOutlined,
                        onClick = GoViewModel::GoOnCloseWindow,
                    )
                }
            }
        }
    }
}

@Composable
private fun GoDeviceListCard(GoState: PairingUiState, GoViewModel: PairingViewModel) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Paired devices", style = AppTheme.typography.h4)

            if (GoState.GoDevices.isEmpty()) {
                Text(text = "No paired devices")
            } else {
                GoState.GoDevices.forEach { GoDevice ->
                    Text(text = GoDevice.GoDeviceName)
                    Text(
                        text = "Role: ${GoDevice.GoRole}",
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
}
