package com.glassstorm.phonemanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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

private const val MASKED_PASSPHRASE = "••••••••"

/**
 * Ecosystem hub status: the run state as a status hero, the bound port, the
 * hotspot credentials and the start/stop actions.
 *
 * [viewModelFactory] is the Dagger-backed factory the shell threads down: the
 * ViewModel and its ports are resolved from the compile-time graph, so a wiring
 * error is a build failure rather than an "unavailable" state. A port that is
 * present but FAILING renders as the calm `無法使用` status instead of crashing.
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
        connectionCard(state, viewModel)
    }
}

@Composable
private fun hubCard(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
) {
    SectionCard(title = "中樞") {
        StatusChip(status = state.hubStatus(), label = state.hubStatusLabel())
        StatTile(value = state.boundPort.toString(), label = "連接埠")

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
private fun connectionCard(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
) {
    var passphraseVisible by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    SectionCard(title = "連線") {
        StatusChip(status = state.connectionStatus(), label = state.connectionStatusLabel())

        if (!state.hotspotAvailable) {
            Text(
                text = "熱點無法使用，請檢查系統設定。",
                style = AppTheme.typography.body2,
                color = AppTheme.colors.textSecondary,
            )
            return@SectionCard
        }

        state.hotspotError?.let {
            Text(text = it, style = AppTheme.typography.body2, color = AppTheme.colors.textSecondary)
        }

        val hotspot = state.hotspot
        if (hotspot == null) {
            Text(
                text = "尚未啟動熱點。",
                style = AppTheme.typography.body2,
                color = AppTheme.colors.textSecondary,
            )
        } else {
            InfoRow(label = "熱點名稱", value = hotspot.ssid)
            InfoRow(
                label = "熱點密碼",
                value = if (passphraseVisible) hotspot.passphrase else MASKED_PASSPHRASE,
                onCopy = { clipboard.setText(AnnotatedString(hotspot.passphrase)) },
            )
            InfoRow(label = "閘道位址", value = hotspot.gatewayIp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hotspot != null) {
                Button(
                    text = if (passphraseVisible) "隱藏密碼" else "顯示密碼",
                    variant = ButtonVariant.Ghost,
                    onClick = { passphraseVisible = !passphraseVisible },
                )
            }
            Button(
                text = if (hotspot == null) "啟動熱點" else "停止熱點",
                variant = if (hotspot == null) ButtonVariant.Primary else ButtonVariant.SecondaryOutlined,
                onClick = if (hotspot == null) viewModel::onStartHotspot else viewModel::onStopHotspot,
            )
        }
    }
}

private fun DashboardUiState.hubStatus(): HubStatus =
    when {
        !hubAvailable -> HubStatus.Error
        running -> HubStatus.Live
        else -> HubStatus.Idle
    }

private fun DashboardUiState.hubStatusLabel(): String =
    when {
        !hubAvailable -> "無法使用"
        running -> "運作中"
        else -> "已停止"
    }

private fun DashboardUiState.connectionStatus(): HubStatus =
    when {
        !hotspotAvailable -> HubStatus.Error
        hotspot != null -> HubStatus.Live
        else -> HubStatus.Idle
    }

private fun DashboardUiState.connectionStatusLabel(): String =
    when {
        !hotspotAvailable -> "無法使用"
        hotspot != null -> "已連線"
        else -> "待機"
    }
