package com.glassstorm.phonemanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glassstorm.phonemanager.ui.components.Text
import com.glassstorm.phonemanager.ui.hub.EmptyState
import com.glassstorm.phonemanager.ui.hub.HubContentColumn
import com.glassstorm.phonemanager.ui.hub.HubStatus
import com.glassstorm.phonemanager.ui.hub.ScreenHeader
import com.glassstorm.phonemanager.ui.hub.SectionCard
import com.glassstorm.phonemanager.ui.hub.StatTile
import com.glassstorm.phonemanager.ui.hub.StatusChip
import com.glassstorm.phonemanager.ui.hub.TagChip
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Live-relay observability: who is attached, what the relay has carried, and the
 * latest recognized transcript and gesture.
 *
 * The page observes the always-on relay rather than opening a session of its own,
 * so there is no start/stop control: a peer appears here as soon as it binds its
 * identity with a `stream_open` frame. Video is NEVER rendered. The phone is the
 * hub, not a viewer — it decodes no H.264, so only counters are shown, and the
 * image/gesture channel (JPEG stills) is counted separately from H.264 NALs.
 *
 * The latest results are only shown for the LIVE hub: the ViewModel clears them
 * when the hub stops or the peer list empties, and each card carries a
 * `最後更新 HH:mm:ss` line so an old result is visibly old.
 *
 * A FAILING `StreamService` renders the calm `無法使用` status rather than
 * crashing the shell; the pre-read state (`available == null`) renders a quiet
 * `檢查中` placeholder instead of flashing an error.
 */
@Composable
fun StreamScreen(
    viewModelFactory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
) {
    val viewModel: StreamViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()

    HubContentColumn(modifier = modifier) {
        ScreenHeader(title = "即時轉送", subtitle = "連線裝置與辨識結果")

        when (state.available) {
            null -> checkingCard()
            false -> unavailableCard()
            true -> {
                peersCard(state)
                countersCard(state)
                transcriptCard(state)
                gestureCard(state)
            }
        }
    }
}

@Composable
private fun checkingCard() {
    SectionCard(title = "狀態") {
        StatusChip(status = HubStatus.Working, label = "檢查中")
        Text(
            text = "正在讀取串流狀態。",
            style = AppTheme.typography.body2,
            color = AppTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun unavailableCard() {
    SectionCard(title = "狀態") {
        StatusChip(status = HubStatus.Error, label = "無法使用")
        Text(
            text = "串流服務暫時無法使用。",
            style = AppTheme.typography.body2,
            color = AppTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun peersCard(state: StreamUiState) {
    SectionCard(title = "連線裝置") {
        val connected = state.hubRunning && state.livePeers.isNotEmpty()
        StatusChip(
            status = if (connected) HubStatus.Live else HubStatus.Idle,
            label = if (connected) "已連線" else "待機",
        )
        if (state.livePeers.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Person,
                title = "尚無裝置",
                hint =
                    if (state.hubRunning) {
                        "裝置連上中樞後會顯示在這裡"
                    } else {
                        "請到總覽啟動中樞"
                    },
            )
        } else {
            state.livePeers.forEach { peer ->
                TagChip(label = "${peer.role.name} · ${peer.deviceId}")
            }
        }
    }
}

@Composable
private fun countersCard(state: StreamUiState) {
    SectionCard(title = "轉送統計") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile(
                    value = state.audioFrames.toString(),
                    label = "音訊",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = state.imageFrames.toString(),
                    label = "影像",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = state.gestures.toString(),
                    label = "手勢",
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile(
                    value = state.transcripts.toString(),
                    label = "字幕",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = state.videoFrames.toString(),
                    label = "H.264",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun transcriptCard(state: StreamUiState) {
    SectionCard(title = "最新字幕") {
        val transcript = state.latestTranscript
        if (transcript == null) {
            EmptyState(
                icon = Icons.Default.Notifications,
                title = "尚無字幕",
                hint = "對方說話時，字幕會顯示在這裡",
            )
        } else {
            state.latestSpeakerLabel?.takeIf { it.isNotBlank() }?.let {
                TagChip(label = it, icon = Icons.Default.Person)
            }
            Text(
                text = transcript,
                style = AppTheme.typography.h1,
                color = AppTheme.colors.text,
            )
            state.latestTranscriptAt?.let { updatedLine(it) }
        }
    }
}

@Composable
private fun gestureCard(state: StreamUiState) {
    SectionCard(title = "最新手勢") {
        val gesture = state.latestGesture
        if (gesture == null) {
            EmptyState(
                icon = Icons.Default.Info,
                title = "尚無手勢",
                hint = "對方比手勢時，結果會顯示在這裡",
            )
        } else {
            Text(
                text = gesture,
                style = AppTheme.typography.h2,
                color = AppTheme.colors.text,
            )
            state.latestGestureAt?.let { updatedLine(it) }
        }
    }
}

@Composable
private fun updatedLine(receivedAtMs: Long) {
    Text(
        text = formatUpdatedAt(receivedAtMs),
        style = AppTheme.typography.body2,
        color = AppTheme.colors.textSecondary,
    )
}

/** `最後更新 HH:mm:ss` in the device time zone, from a received-at wall clock. */
internal fun formatUpdatedAt(receivedAtMs: Long): String {
    val time = Instant.ofEpochMilli(receivedAtMs).atZone(ZoneId.systemDefault()).toLocalTime()
    return "最後更新 ${UPDATED_AT_FORMATTER.format(time)}"
}

private val UPDATED_AT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
