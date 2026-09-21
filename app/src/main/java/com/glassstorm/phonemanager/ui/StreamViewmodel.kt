package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContextOrNull
import com.glassstorm.phonemanager.domain.dto.RelayStats
import com.glassstorm.phonemanager.domain.service.PairingService
import com.glassstorm.phonemanager.domain.service.StreamService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Stream state holder.
 *
 * It resolves its collaborators from the [Context] registry by the DOMAIN
 * interface type and keeps an absent port as `null`, which is what drives the
 * "not available" rendering instead of a crash.
 *
 * ## Per-session counters
 *
 * `StreamService.stats()` reports CUMULATIVE totals for the relay's lifetime,
 * so the ViewModel snapshots the counters when a session starts ([baseline])
 * and renders the difference. A second session therefore starts at zero instead
 * of inheriting the previous session's frames, and stopping returns to the idle
 * zeros by clearing the state outright.
 *
 * ## Cadence and lifecycle
 *
 * Two jobs run while a session is live: a poll loop refreshing the counters on a
 * bounded interval, and a collector over `results(sessionId)`. Both live in
 * `viewModelScope`, so a stop cancels them explicitly and a cleared ViewModel
 * cancels them automatically — neither can outlive the session or the screen.
 */
class StreamViewModel(
    private val context: Context,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : ViewModel() {
    private val stream: StreamService? = FromContextOrNull<StreamService>(context)
    private val pairing: PairingService? = FromContextOrNull<PairingService>(context)

    private val state = MutableStateFlow(StreamUiState(available = stream != null))

    val uiState: StateFlow<StreamUiState> = state.asStateFlow()

    private var baseline: RelayStats = zeroStats
    private var pollJob: Job? = null
    private var resultsJob: Job? = null

    /** Open a relay session for the first paired peer and start refreshing it. */
    fun onStart() {
        val service = stream ?: return
        if (state.value.sessionId != null) return

        baseline = service.stats()
        val peerId =
            pairing?.listPaired()?.firstOrNull()?.deviceId ?: DEFAULT_PEER_ID
        val session = service.openSession(peerId)

        state.value =
            state.value.copy(
                sessionId = session.sessionId,
                peerId = session.deviceId,
            )
        refresh()

        pollJob =
            viewModelScope.launch {
                while (true) {
                    delay(pollIntervalMs)
                    refresh()
                }
            }
        resultsJob = viewModelScope.launch { collectResults(session.sessionId) }
    }

    /** Close the live session and return the screen to its idle zeros. */
    fun onStop() {
        val service = stream ?: return
        val sessionId = state.value.sessionId ?: return

        cancelJobs()
        state.value = StreamUiState(available = true)
        viewModelScope.launch { service.closeSession(sessionId) }
    }

    private fun cancelJobs() {
        pollJob?.cancel()
        pollJob = null
        resultsJob?.cancel()
        resultsJob = null
    }

    private suspend fun collectResults(sessionId: String) {
        stream?.results(sessionId)?.collect { result ->
            state.value =
                state.value.copy(
                    latestTranscript = result.text,
                    latestSpeakerLabel = result.speakerLabel,
                )
        }
    }

    private fun refresh() {
        val service = stream ?: return
        val stats = service.stats()
        state.value =
            state.value.copy(
                audioFrames = stats.audioFrames - baseline.audioFrames,
                videoFrames = stats.videoFrames - baseline.videoFrames,
                videoDropped = stats.videoDropped - baseline.videoDropped,
                transcripts = stats.transcripts - baseline.transcripts,
                liveSessions = stats.liveSessions,
            )
    }

    companion object {
        /** Counter refresh cadence. Short enough to look live, long enough not to spin. */
        const val DEFAULT_POLL_INTERVAL_MS: Long = 250L

        /** Peer used when no paired device exists yet: the session is still real. */
        const val DEFAULT_PEER_ID: String = "local-peer"
    }
}

private val zeroStats =
    RelayStats(
        audioFrames = 0L,
        videoFrames = 0L,
        videoDropped = 0L,
        transcripts = 0L,
        liveSessions = 0,
    )
