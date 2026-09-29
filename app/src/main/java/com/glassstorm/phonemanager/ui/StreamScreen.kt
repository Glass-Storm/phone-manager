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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
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
 * A FAILING `StreamService` renders "not available" rather than crashing the shell.
 */
@Composable
fun StreamScreen(
    viewModelFactory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
) {
    val viewModel: StreamViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Stream", style = AppTheme.typography.h2)

        if (!state.available) {
            Text(text = "Stream service not available")
            return@Column
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

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Session", style = AppTheme.typography.h4)

            Text(text = if (live) "Session: live" else "Session: idle")
            state.peerId?.let { Text(text = "Peer: $it") }
            Text(text = "Live sessions: ${state.liveSessions}")

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    text = "Start session",
                    enabled = !live,
                    onClick = viewModel::onStart,
                )
                Button(
                    text = "Stop session",
                    variant = ButtonVariant.DestructiveOutlined,
                    enabled = live,
                    onClick = viewModel::onStop,
                )
            }
        }
    }
}

@Composable
private fun countersCard(state: StreamUiState) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Relay counters", style = AppTheme.typography.h4)

            Text(text = "Audio frames in: ${state.audioFrames}")
            Text(text = "Video frames in: ${state.videoFrames}")
            Text(text = "Video dropped: ${state.videoDropped}")
            Text(text = "Transcripts out: ${state.transcripts}")
        }
    }
}

@Composable
private fun transcriptCard(state: StreamUiState) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Latest transcript", style = AppTheme.typography.h4)

            val text = state.latestTranscript
            if (text == null) {
                Text(text = "No transcript yet")
            } else {
                Text(text = text)
                Text(
                    text = "Speaker: ${state.latestSpeakerLabel.orEmpty()}",
                    style = AppTheme.typography.label2,
                )
            }
        }
    }
}
