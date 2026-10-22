package com.glassstorm.phonemanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
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
import com.glassstorm.phonemanager.ui.hub.EmptyState
import com.glassstorm.phonemanager.ui.hub.HubContentColumn
import com.glassstorm.phonemanager.ui.hub.HubStatus
import com.glassstorm.phonemanager.ui.hub.InfoRow
import com.glassstorm.phonemanager.ui.hub.ScreenHeader
import com.glassstorm.phonemanager.ui.hub.SectionCard
import com.glassstorm.phonemanager.ui.hub.StatTile
import com.glassstorm.phonemanager.ui.hub.StatusChip
import com.glassstorm.phonemanager.ui.hub.TagChip

/**
 * Live relay session view: the session state, the relay counters and the latest
 * recognized utterance as the largest text on the screen.
 *
 * Video is NEVER rendered here. The phone is the hub, not a viewer — it decodes no
 * H.264, so the screen shows the video counters (including the drop-oldest
 * evictions) and nothing else. There is no recording or playback either.
 *
 * A FAILING `StreamService` renders the calm `無法使用` status rather than
 * crashing the shell.
 */
@Composable
fun StreamScreen(
    viewModelFactory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
) {
    val viewModel: StreamViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()

    HubContentColumn(modifier = modifier) {
        ScreenHeader(title = "即時串流", subtitle = "字幕與手勢即時轉送")

        if (!state.available) {
            SectionCard(title = "工作階段") {
                StatusChip(status = HubStatus.Error, label = "無法使用")
                Text(
                    text = "串流服務暫時無法使用。",
                    style = AppTheme.typography.body2,
                    color = AppTheme.colors.textSecondary,
                )
            }
            return@HubContentColumn
        }

        sessionCard(state, viewModel)
        countersCard(state)
        transcriptCard(state)
    }
}

@Composable
private fun sessionCard(
    state: StreamUiState,
    viewModel: StreamViewModel,
) {
    val live = state.sessionId != null

    SectionCard(title = "工作階段") {
        StatusChip(
            status = if (live) HubStatus.Live else HubStatus.Idle,
            label = if (live) "已連線" else "待機",
        )
        InfoRow(label = "目前連線", value = state.liveSessions.toString())
        state.peerId?.let { InfoRow(label = "對象裝置", value = it) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                text = "開始串流",
                enabled = !live,
                onClick = viewModel::onStart,
            )
            Button(
                text = "停止串流",
                variant = ButtonVariant.DestructiveOutlined,
                enabled = live,
                onClick = viewModel::onStop,
            )
        }
    }
}

@Composable
private fun countersCard(state: StreamUiState) {
    SectionCard(title = "轉送統計") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile(
                value = state.audioFrames.toString(),
                label = "音訊輸入",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = state.videoFrames.toString(),
                label = "視訊輸入",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = state.videoDropped.toString(),
                label = "視訊捨棄",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = state.transcripts.toString(),
                label = "字幕輸出",
                modifier = Modifier.weight(1f),
            )
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
        }
    }
}
