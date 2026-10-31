package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.model.RelayOutput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Live-relay state holder.
 *
 * The page no longer opens a relay session of its own: the hub's gRPC stream is
 * always-on, and a peer binds its identity with the `stream_open` frame. This
 * ViewModel therefore OBSERVES the relay through the [StreamService] port:
 *
 *  * `stats()` and `livePeers()` are in-memory snapshots that cannot block, so a
 *    short poll keeps the counters and the attached-peer list fresh;
 *  * `outputs()` is a hot flow of every relay output from every live session, so
 *    the latest transcript and gesture render as they are produced. The flow has
 *    NO replay, so a fresh ViewModel starts empty rather than showing a previous
 *    session's last result.
 *
 * ## Dead sessions never keep the cards alive
 *
 * The results belong to the LIVE hub. [refresh] clears the latest transcript and
 * gesture whenever [HubServer.isRunning] is false or `livePeers()` is empty, so a
 * page whose peer list says 待機 can never still show 最新字幕/最新手勢 from a
 * session that has gone away. Each accepted output stamps a received-at instant
 * so the card can render `最後更新 HH:mm:ss` and make age visible instead of
 * pretending the result is current.
 *
 * A FAILING port degrades to the "not available" rendering instead of a crash; a
 * missing port is impossible under compile-time DI. Both jobs run in
 * `viewModelScope`, so a cleared ViewModel stops them automatically.
 */
class StreamViewModel
    private constructor(
        private val stream: StreamService,
        private val hub: HubServer,
        private val clock: () -> Long,
        private val pollIntervalMs: Long,
        refreshOnInit: Boolean,
    ) : ViewModel() {
        @Inject
        constructor(
            stream: StreamService,
            hub: HubServer,
        ) : this(
            stream = stream,
            hub = hub,
            clock = System::currentTimeMillis,
            pollIntervalMs = DEFAULT_POLL_INTERVAL_MS,
            refreshOnInit = true,
        )

        private val state = MutableStateFlow(StreamUiState())

        val uiState: StateFlow<StreamUiState> = state.asStateFlow()

        init {
            // Seed the hub's running state SYNCHRONOUSLY, before either job starts.
            // The poll loop and the output collector are launched together, so a
            // replayed latest output can be delivered before the first refresh has
            // set `hubRunning`; without this seed applyOutput's stopped-hub guard
            // would drop a result the live hub really produced (the 即時 page's
            // "counter increments but 最新字幕 stays empty" bug).
            state.value =
                state.value.copy(
                    hubRunning = runCatching { hub.isRunning() }.getOrDefault(false),
                )
            if (refreshOnInit) {
                viewModelScope.launch {
                    while (true) {
                        refresh()
                        delay(pollIntervalMs)
                    }
                }
            }
            viewModelScope.launch {
                runCatching {
                    stream.outputs().collect(::applyOutput)
                }
            }
        }

        private fun refresh() {
            val stats = runCatching { stream.stats() }
            val peers = runCatching { stream.livePeers() }
            val hubRunning = runCatching { hub.isRunning() }.getOrDefault(false)
            val current = state.value
            // A stopped hub has no live peers regardless of what the relay's
            // in-memory registry still reports: teardown races the poll.
            val resolvedPeers = if (hubRunning) peers.getOrNull() ?: current.livePeers else emptyList()
            val clearLatest = !hubRunning || resolvedPeers.isEmpty()

            state.value =
                current.copy(
                    available = stats.isSuccess,
                    hubRunning = hubRunning,
                    audioFrames = stats.getOrNull()?.audioFrames ?: current.audioFrames,
                    videoFrames = stats.getOrNull()?.videoFrames ?: current.videoFrames,
                    imageFrames = stats.getOrNull()?.imageFrames ?: current.imageFrames,
                    gestures = stats.getOrNull()?.gestures ?: current.gestures,
                    transcripts = stats.getOrNull()?.transcripts ?: current.transcripts,
                    livePeers = resolvedPeers,
                    latestTranscript = if (clearLatest) null else current.latestTranscript,
                    latestSpeakerLabel = if (clearLatest) null else current.latestSpeakerLabel,
                    latestTranscriptAt = if (clearLatest) null else current.latestTranscriptAt,
                    latestGesture = if (clearLatest) null else current.latestGesture,
                    latestGestureAt = if (clearLatest) null else current.latestGestureAt,
                )
        }

        private fun applyOutput(output: RelayOutput) {
            // A stopped hub cannot produce a current result: never let a late
            // emission from a torn-down session repopulate the cards.
            if (!state.value.hubRunning) return
            when (output) {
                is RelayOutput.Speech -> {
                    val text = output.result.text.takeIf { it.isNotBlank() } ?: return
                    val receivedAt = clock()
                    state.value =
                        state.value.copy(
                            latestTranscript = text,
                            latestSpeakerLabel = output.result.speakerLabel.ifBlank { null },
                            latestTranscriptAt = receivedAt,
                        )
                }
                is RelayOutput.Gesture -> {
                    val text = output.text.takeIf { it.isNotBlank() } ?: return
                    state.value = state.value.copy(latestGesture = text, latestGestureAt = clock())
                }
                else -> Unit
            }
        }

        companion object {
            /** Snapshot refresh cadence. Short enough to feel live, long enough not to spin. */
            const val DEFAULT_POLL_INTERVAL_MS: Long = 500L

            /**
             * Test seam: builds the ViewModel with an explicit clock, poll cadence
             * and an option to skip the initial read, so the pre-read `null`
             * availability state can be asserted without racing a real coroutine.
             */
            fun forTesting(
                stream: StreamService,
                hub: HubServer,
                clock: () -> Long = System::currentTimeMillis,
                pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
                refreshOnInit: Boolean = true,
            ): StreamViewModel = StreamViewModel(stream, hub, clock, pollIntervalMs, refreshOnInit)
        }
    }
