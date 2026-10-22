package com.glassstorm.phonemanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import com.glassstorm.phonemanager.ui.hub.HubContentColumn
import com.glassstorm.phonemanager.ui.hub.HubStatus
import com.glassstorm.phonemanager.ui.hub.InfoRow
import com.glassstorm.phonemanager.ui.hub.ScreenHeader
import com.glassstorm.phonemanager.ui.hub.SectionCard
import com.glassstorm.phonemanager.ui.hub.StatusChip

/** The `AppConfig` label strings the ViewModel publishes for each hotspot mode. */
private const val MODE_MANUAL = "Manual"

private const val MODE_AUTO = "Auto"

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

    HubContentColumn(modifier = modifier) {
        ScreenHeader(title = "系統設定", subtitle = "中樞與連線偏好")

        if (!state.configAvailable) {
            SectionCard(title = "設定") {
                StatusChip(status = HubStatus.Error, label = "無法使用")
                Text(
                    text = "設定暫時無法讀取。",
                    style = AppTheme.typography.body2,
                    color = AppTheme.colors.textSecondary,
                )
            }
            return@HubContentColumn
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
    SectionCard(title = "熱點") {
        InfoRow(label = "目前模式", value = localisedMode(state.hotspotMode))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                text = "手動",
                variant = if (state.hotspotMode == MODE_MANUAL) ButtonVariant.Primary else ButtonVariant.SecondaryOutlined,
                onClick = { viewModel.onSelectHotspotMode(HotspotMode.MANUAL) },
            )
            Button(
                text = "自動",
                variant = if (state.hotspotMode == MODE_AUTO) ButtonVariant.Primary else ButtonVariant.SecondaryOutlined,
                onClick = { viewModel.onSelectHotspotMode(HotspotMode.AUTO) },
            )
        }
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
        InfoRow(label = "連接埠", value = state.hubPort.toString())
    }
}

/**
 * The ViewModel publishes the raw `AppConfig` label; the screen renders the
 * plain-language value. It deliberately differs from the button labels (手動 /
 * 自動) so a text query names exactly one node.
 */
private fun localisedMode(mode: String): String =
    when (mode) {
        MODE_MANUAL -> "手動模式"
        MODE_AUTO -> "自動模式"
        else -> mode
    }
