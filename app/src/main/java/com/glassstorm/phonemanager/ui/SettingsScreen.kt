package com.glassstorm.phonemanager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glassstorm.phonemanager.ui.components.Button
import com.glassstorm.phonemanager.ui.components.Text
import com.glassstorm.phonemanager.ui.hub.HubContentColumn
import com.glassstorm.phonemanager.ui.hub.HubStatus
import com.glassstorm.phonemanager.ui.hub.InfoRow
import com.glassstorm.phonemanager.ui.hub.ScreenHeader
import com.glassstorm.phonemanager.ui.hub.SectionCard
import com.glassstorm.phonemanager.ui.hub.StatusChip

/**
 * Hub configuration: the Doze exemption and the protocol/port the hub exposes.
 *
 * The hotspot mode selector is gone with the programmatic hotspot, and the live
 * relay diagnostics moved to the 即時 page, so this screen has no misleading
 * control. Speech-to-text is not configurable: the hub runs a single on-device
 * engine, so there is no engine selector and no cloud credential field.
 */
@Composable
fun SettingsScreen(
    viewModelFactory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()

    // The exemption is granted in an EXTERNAL Android settings activity, so the
    // screen is backgrounded while it is granted. Refresh on return to reflect it.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onRefresh() }

    HubContentColumn(modifier = modifier) {
        ScreenHeader(title = "系統設定", subtitle = "中樞與連線偏好")

        batteryCard(state, viewModel)
        endpointCard(state)
    }
}

@Composable
private fun batteryCard(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    SectionCard(title = "電池") {
        if (!state.batteryAvailable) {
            StatusChip(status = HubStatus.Error, label = "無法使用")
            Text(
                text = "電池狀態暫時無法取得。",
                style = AppTheme.typography.body2,
                color = AppTheme.colors.textSecondary,
            )
            return@SectionCard
        }

        InfoRow(
            label = "最佳化狀態",
            value = if (state.batteryExempt) "已排除" else "未排除",
        )
        Button(
            text = "要求排除電池最佳化",
            enabled = !state.batteryExempt,
            onClick = viewModel::onRequestBatteryExemption,
        )
    }
}

@Composable
private fun endpointCard(state: SettingsUiState) {
    SectionCard(title = "端點") {
        InfoRow(label = "通訊協定", value = state.protocol)
        if (state.hubPort > 0) {
            InfoRow(label = "連接埠", value = state.hubPort.toString())
        }
    }
}
