package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.model.RelayStats
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Stream state holder.
 *
 * The `StreamService` and `PairingService` ports are CONSTRUCTOR dependencies. A
 * FAILING port degrades to the "not available" rendering instead of a crash; a
 * missing port is impossible under compile-time DI.
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
class StreamViewModel
    private constructor(
        private val stream: StreamService,
        private val pairing: PairingService,
        private val pollIntervalMs: Long,
    ) : ViewModel() {
        @Inject
        constructor(
            stream: StreamService,
            pairing: PairingService,
        ) : this(stream, pairing, DEFAULT_POLL_INTERVAL_MS)

        private val state = MutableStateFlow(StreamUiState())

        val uiState: StateFlow<StreamUiState> = state.asStateFlow()

        private var baseline: RelayStats = zeroStats
        private var pollJob: Job? = null
        private var resultsJob: Job? = null

        init {
            refreshAvailability()
        }

        /** Open a relay session for the first paired peer and start refreshing it. */
        fun onStart() {
            if (state.value.sessionId != null) return

            val stats = runCatching { stream.stats() }
            if (stats.isFailure) {
                state.value = state.value.copy(available = false)
                return
            }
            baseline = stats.getOrThrow()
            val peerId = runCatching { pairing.listPaired().firstOrNull()?.deviceId }.getOrNull() ?: DEFAULT_PEER_ID
            val session = runCatching { stream.openSession(peerId) }
            if (session.isFailure) {
                state.value = state.value.copy(available = false)
                return
            }
            val opened = session.getOrThrow()

            state.value =
                state.value.copy(
                    available = true,
                    sessionId = opened.sessionId,
                    peerId = opened.deviceId,
                )
            refresh()

            pollJob =
                viewModelScope.launch {
                    while (true) {
                        delay(pollIntervalMs)
                        refresh()
                    }
                }
            resultsJob = viewModelScope.launch { collectResults(opened.sessionId) }
        }

        /** Close the live session and return the screen to its idle zeros. */
        fun onStop() {
            val sessionId = state.value.sessionId ?: return

            cancelJobs()
            state.value = StreamUiState(available = true)
            viewModelScope.launch { runCatching { stream.closeSession(sessionId) } }
        }

        private fun cancelJobs() {
            pollJob?.cancel()
            pollJob = null
            resultsJob?.cancel()
            resultsJob = null
        }

        private suspend fun collectResults(sessionId: String) {
            runCatching {
                stream.results(sessionId).collect { result ->
                    state.value =
                        state.value.copy(
                            latestTranscript = result.text,
                            latestSpeakerLabel = result.speakerLabel,
                        )
                }
            }
        }

        private fun refreshAvailability() {
            val stats = runCatching { stream.stats() }
            state.value = state.value.copy(available = stats.isSuccess)
        }

        private fun refresh() {
            val stats = runCatching { stream.stats() }
            if (stats.isFailure) {
                state.value = state.value.copy(available = false)
                return
            }
            val value = stats.getOrThrow()
            state.value =
                state.value.copy(
                    available = true,
                    audioFrames = value.audioFrames - baseline.audioFrames,
                    videoFrames = value.videoFrames - baseline.videoFrames,
                    videoDropped = value.videoDropped - baseline.videoDropped,
                    transcripts = value.transcripts - baseline.transcripts,
                    liveSessions = value.liveSessions,
                )
        }

        companion object {
            /** Counter refresh cadence. Short enough to look live, long enough not to spin. */
            const val DEFAULT_POLL_INTERVAL_MS: Long = 250L

            /** Peer used when no paired device exists yet: the session is still real. */
            const val DEFAULT_PEER_ID: String = "local-peer"

            /**
             * Test seam: builds the ViewModel with an explicit poll interval so the
             * cadence is deterministic. Production uses the `@Inject` constructor.
             */
            fun forTesting(
                stream: StreamService,
                pairing: PairingService,
                pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
            ): StreamViewModel = StreamViewModel(stream, pairing, pollIntervalMs)
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
