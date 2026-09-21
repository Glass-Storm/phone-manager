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
 * Live relay session view: start/stop a session, watch the relay counters and read
 * the latest recognized utterance.
 *
 * Video is NEVER rendered here. The phone is the hub, not a viewer — it decodes no
 * H.264, so the screen shows the video counters (including the drop-oldest
 * evictions) and nothing else. There is no recording or playback either.
 *
 * An absent `StreamService` renders "not available" rather than crashing the shell.
 */
@Composable
fun StreamScreen(
    GoContext: Context,
    modifier: Modifier = Modifier,
) {
    val GoViewModel: StreamViewModel = viewModel { StreamViewModel(GoContext) }
    val GoState by GoViewModel.GoUiState.collectAsState()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Stream", style = AppTheme.typography.h2)

        if (!GoState.GoAvailable) {
            Text(text = "Stream service not available")
            return@Column
        }

        GoSessionCard(GoState, GoViewModel)

        GoCountersCard(GoState)

        GoTranscriptCard(GoState)
    }
}

@Composable
private fun GoSessionCard(
    GoState: StreamUiState,
    GoViewModel: StreamViewModel,
) {
    val GoLive = GoState.GoSessionId != null

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Session", style = AppTheme.typography.h4)

            Text(text = if (GoLive) "Session: live" else "Session: idle")
            GoState.GoPeerId?.let { Text(text = "Peer: $it") }
            Text(text = "Live sessions: ${GoState.GoLiveSessions}")

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    text = "Start session",
                    enabled = !GoLive,
                    onClick = GoViewModel::GoOnStart,
                )
                Button(
                    text = "Stop session",
                    variant = ButtonVariant.DestructiveOutlined,
                    enabled = GoLive,
                    onClick = GoViewModel::GoOnStop,
                )
            }
        }
    }
}

@Composable
private fun GoCountersCard(GoState: StreamUiState) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Relay counters", style = AppTheme.typography.h4)

            Text(text = "Audio frames in: ${GoState.GoAudioFrames}")
            Text(text = "Video frames in: ${GoState.GoVideoFrames}")
            Text(text = "Video dropped: ${GoState.GoVideoDropped}")
            Text(text = "Transcripts out: ${GoState.GoTranscripts}")
        }
    }
}

@Composable
private fun GoTranscriptCard(GoState: StreamUiState) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Latest transcript", style = AppTheme.typography.h4)

            val GoText = GoState.GoLatestTranscript
            if (GoText == null) {
                Text(text = "No transcript yet")
            } else {
                Text(text = GoText)
                Text(
                    text = "Speaker: ${GoState.GoLatestSpeakerLabel.orEmpty()}",
                    style = AppTheme.typography.label2,
                )
            }
        }
    }
}
