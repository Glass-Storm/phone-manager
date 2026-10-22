package com.glassstorm.phonemanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glassstorm.phonemanager.core.model.Device
import com.glassstorm.phonemanager.ui.components.Button
import com.glassstorm.phonemanager.ui.components.ButtonVariant
import com.glassstorm.phonemanager.ui.components.HorizontalDivider
import com.glassstorm.phonemanager.ui.components.Text
import com.glassstorm.phonemanager.ui.hub.EmptyState
import com.glassstorm.phonemanager.ui.hub.HubContentColumn
import com.glassstorm.phonemanager.ui.hub.HubStatus
import com.glassstorm.phonemanager.ui.hub.ScreenHeader
import com.glassstorm.phonemanager.ui.hub.SectionCard
import com.glassstorm.phonemanager.ui.hub.StatusChip
import com.glassstorm.phonemanager.ui.hub.TagChip

/**
 * Known-device management: every device that has bound its identity to the hub,
 * its role, and when it was last seen. Revoking removes the device row.
 *
 * The list comes from the `DeviceRepository` domain port; the screen never names
 * an adapter. A failing store renders the calm `無法使用` status rather than
 * crashing.
 */
@Composable
fun DevicesScreen(
    viewModelFactory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
    nowMs: () -> Long = { System.currentTimeMillis() },
) {
    val viewModel: DevicesViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()

    HubContentColumn(modifier = modifier) {
        ScreenHeader(title = "已連線裝置", subtitle = "曾在此中樞連線的裝置")

        if (!state.available) {
            SectionCard(title = "裝置清單") {
                StatusChip(status = HubStatus.Error, label = "無法使用")
                Text(
                    text = "裝置清單暫時無法使用。",
                    style = AppTheme.typography.body2,
                    color = AppTheme.colors.textSecondary,
                )
            }
            return@HubContentColumn
        }

        SectionCard(title = "裝置清單") {
            if (state.devices.isEmpty()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = "尚無裝置",
                    hint = "眼鏡或服務連線後，會顯示在這裡",
                )
            } else {
                val now = nowMs()
                state.devices.forEachIndexed { index, device ->
                    DeviceRow(
                        device = device,
                        nowMs = now,
                        onRevoke = { viewModel.onRevoke(device.deviceId) },
                    )
                    if (index != state.devices.lastIndex) {
                        HorizontalDivider(color = AppTheme.colors.disabled)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: Device,
    nowMs: Long,
    onRevoke: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = device.deviceName,
                style = AppTheme.typography.body1,
                color = AppTheme.colors.text,
            )
            TagChip(label = device.role)
            Text(
                text = "最後連線：${lastSeenPhrase(device.lastSeenMs, nowMs)}",
                style = AppTheme.typography.label2,
                color = AppTheme.colors.textSecondary,
            )
        }
        Button(
            text = "移除 ${device.deviceName}",
            variant = ButtonVariant.DestructiveOutlined,
            onClick = onRevoke,
        )
    }
}

/**
 * A human, localised phrase for when a device was last seen, instead of a raw
 * ISO-8601 instant. The clock is a parameter so the buckets are deterministic
 * under test; a device never seen since pairing keeps the plain 未曾連線 line.
 */
private fun lastSeenPhrase(
    lastSeenMs: Long?,
    nowMs: Long,
): String {
    if (lastSeenMs == null) return "未曾連線"
    val elapsedMs = (nowMs - lastSeenMs).coerceAtLeast(0L)
    return when {
        elapsedMs < MINUTE_MS -> "剛剛"
        elapsedMs < HOUR_MS -> "${elapsedMs / MINUTE_MS} 分鐘前"
        elapsedMs < DAY_MS -> "${elapsedMs / HOUR_MS} 小時前"
        else -> "${elapsedMs / DAY_MS} 天前"
    }
}

private const val MINUTE_MS = 60_000L

private const val HOUR_MS = 3_600_000L

private const val DAY_MS = 86_400_000L
