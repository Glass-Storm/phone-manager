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
import com.glassstorm.phonemanager.ui.components.Button
import com.glassstorm.phonemanager.ui.components.ButtonVariant
import com.glassstorm.phonemanager.ui.components.Text
import com.glassstorm.phonemanager.ui.hub.HubContentColumn
import com.glassstorm.phonemanager.ui.hub.HubStatus
import com.glassstorm.phonemanager.ui.hub.InfoRow
import com.glassstorm.phonemanager.ui.hub.ScreenHeader
import com.glassstorm.phonemanager.ui.hub.SectionCard
import com.glassstorm.phonemanager.ui.hub.StatTile
import com.glassstorm.phonemanager.ui.hub.StatusChip

/**
 * Ecosystem hub status: the run state as a status hero, the bound port, and the
 * gateway address of the user's manually-enabled hotspot.
 *
 * There is no programmatic hotspot any more: the hotspot belongs to the user, who
 * enables it from Android Settings. The dashboard only reports the detected
 * gateway the peers dial, and never shows or copies the hotspot credentials.
 *
 * The hotspot verdict is `熱點已開啟` / `熱點未開啟`; `已連線` is deliberately
 * absent here, because an access point is not a connected peer. Peer connection
 * vocabulary lives on the 即時 page, where it is only used while the hub runs.
 *
 * [viewModelFactory] is the Dagger-backed factory the shell threads down: the
 * ViewModel and its ports are resolved from the compile-time graph, so a wiring
 * error is a build failure rather than an "unavailable" state. Availability is
 * tri-state, so the pre-read `null` state shows a quiet `檢查中` placeholder; only
 * a resolved failure renders the calm `無法使用` status.
 */
@Composable
fun DashboardScreen(
    viewModelFactory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
) {
    val viewModel: DashboardViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()

    HubContentColumn(modifier = modifier) {
        ScreenHeader(title = "系統總覽", subtitle = "硬體中樞狀態")

        hubCard(state, viewModel)
        hotspotCard(state)
    }
}

@Composable
private fun hubCard(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
) {
    SectionCard(title = "中樞") {
        StatusChip(status = state.hubStatus(), label = state.hubStatusLabel())
        StatTile(
            value = if (state.boundPort > 0) state.boundPort.toString() else "未啟動",
            label = "連接埠",
        )

        state.startError?.let {
            Text(text = it, style = AppTheme.typography.body2, color = AppTheme.colors.textSecondary)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                text = "啟動中樞",
                enabled = !state.running,
                onClick = viewModel::onStartHub,
            )
            Button(
                text = "停止中樞",
                variant = ButtonVariant.SecondaryOutlined,
                enabled = state.running,
                onClick = viewModel::onStopHub,
            )
        }
    }
}

@Composable
private fun hotspotCard(state: DashboardUiState) {
    SectionCard(title = "熱點") {
        StatusChip(status = state.hotspotStatus(), label = state.hotspotStatusLabel())

        when (state.hotspotAvailable) {
            null ->
                Text(
                    text = "正在偵測手機熱點。",
                    style = AppTheme.typography.body2,
                    color = AppTheme.colors.textSecondary,
                )
            false ->
                Text(
                    text = "熱點無法使用，請檢查系統設定。",
                    style = AppTheme.typography.body2,
                    color = AppTheme.colors.textSecondary,
                )
            true -> {
                val hotspot = state.hotspot
                if (hotspot == null) {
                    Text(
                        text = "請到 Android 設定開啟行動熱點，再回到此頁。",
                        style = AppTheme.typography.body2,
                        color = AppTheme.colors.textSecondary,
                    )
                } else {
                    InfoRow(label = "閘道位址", value = hotspot.gatewayIp)
                }
            }
        }
    }
}

private fun DashboardUiState.hubStatus(): HubStatus =
    when {
        hubAvailable == null -> HubStatus.Working
        hubAvailable == false -> HubStatus.Error
        running -> HubStatus.Live
        else -> HubStatus.Idle
    }

private fun DashboardUiState.hubStatusLabel(): String =
    when {
        hubAvailable == null -> "檢查中"
        hubAvailable == false -> "無法使用"
        running -> "運作中"
        else -> "已停止"
    }

private fun DashboardUiState.hotspotStatus(): HubStatus =
    when {
        hotspotAvailable == null -> HubStatus.Working
        hotspotAvailable == false -> HubStatus.Error
        hotspot != null -> HubStatus.Live
        else -> HubStatus.Idle
    }

private fun DashboardUiState.hotspotStatusLabel(): String =
    when {
        hotspotAvailable == null -> "檢查中"
        hotspotAvailable == false -> "無法使用"
        hotspot != null -> "熱點已開啟"
        else -> "熱點未開啟"
    }
