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
 * `StreamService.GoStats()` reports CUMULATIVE totals for the relay's lifetime,
 * so the ViewModel snapshots the counters when a session starts ([GoBaseline])
 * and renders the difference. A second session therefore starts at zero instead
 * of inheriting the previous session's frames, and stopping returns to the idle
 * zeros by clearing the state outright.
 *
 * ## Cadence and lifecycle
 *
 * Two jobs run while a session is live: a poll loop refreshing the counters on a
 * bounded interval, and a collector over `GoResults(sessionId)`. Both live in
 * `viewModelScope`, so a stop cancels them explicitly and a cleared ViewModel
 * cancels them automatically — neither can outlive the session or the screen.
 */
class StreamViewModel(
    private val GoContext: Context,
    private val GoPollIntervalMs: Long = GoDefaultPollIntervalMs,
) : ViewModel() {

    private val GoStream: StreamService? = FromContextOrNull<StreamService>(GoContext)
    private val GoPairing: PairingService? = FromContextOrNull<PairingService>(GoContext)

    private val GoState = MutableStateFlow(StreamUiState(GoAvailable = GoStream != null))

    val GoUiState: StateFlow<StreamUiState> = GoState.asStateFlow()

    private var GoBaseline: RelayStats = GoZeroStats
    private var GoPollJob: Job? = null
    private var GoResultsJob: Job? = null

    /** Open a relay session for the first paired peer and start refreshing it. */
    fun GoOnStart() {
        val GoService = GoStream ?: return
        if (GoState.value.GoSessionId != null) return

        GoBaseline = GoService.GoStats()
        val GoPeerId =
            GoPairing?.GoListPaired()?.firstOrNull()?.GoDeviceId ?: GoDefaultPeerId
        val GoSession = GoService.GoOpenSession(GoPeerId)

        GoState.value = GoState.value.copy(
            GoSessionId = GoSession.GoSessionId,
            GoPeerId = GoSession.GoDeviceId,
        )
        GoRefresh()

        GoPollJob =
            viewModelScope.launch {
                while (true) {
                    delay(GoPollIntervalMs)
                    GoRefresh()
                }
            }
        GoResultsJob = viewModelScope.launch { GoCollectResults(GoSession.GoSessionId) }
    }

    /** Close the live session and return the screen to its idle zeros. */
    fun GoOnStop() {
        val GoService = GoStream ?: return
        val GoSessionId = GoState.value.GoSessionId ?: return

        GoCancelJobs()
        GoState.value = StreamUiState(GoAvailable = true)
        viewModelScope.launch { GoService.GoCloseSession(GoSessionId) }
    }

    private fun GoCancelJobs() {
        GoPollJob?.cancel()
        GoPollJob = null
        GoResultsJob?.cancel()
        GoResultsJob = null
    }

    private suspend fun GoCollectResults(sessionId: String) {
        GoStream?.GoResults(sessionId)?.collect { GoResult ->
            GoState.value =
                GoState.value.copy(
                    GoLatestTranscript = GoResult.GoText,
                    GoLatestSpeakerLabel = GoResult.GoSpeakerLabel,
                )
        }
    }

    private fun GoRefresh() {
        val GoService = GoStream ?: return
        val GoStats = GoService.GoStats()
        GoState.value =
            GoState.value.copy(
                GoAudioFrames = GoStats.GoAudioFrames - GoBaseline.GoAudioFrames,
                GoVideoFrames = GoStats.GoVideoFrames - GoBaseline.GoVideoFrames,
                GoVideoDropped = GoStats.GoVideoDropped - GoBaseline.GoVideoDropped,
                GoTranscripts = GoStats.GoTranscripts - GoBaseline.GoTranscripts,
                GoLiveSessions = GoStats.GoLiveSessions,
            )
    }

    companion object {
        /** Counter refresh cadence. Short enough to look live, long enough not to spin. */
        const val GoDefaultPollIntervalMs: Long = 250L

        /** Peer used when no paired device exists yet: the session is still real. */
        const val GoDefaultPeerId: String = "local-peer"
    }
}

private val GoZeroStats = RelayStats(
    GoAudioFrames = 0L,
    GoVideoFrames = 0L,
    GoVideoDropped = 0L,
    GoTranscripts = 0L,
    GoLiveSessions = 0,
)
