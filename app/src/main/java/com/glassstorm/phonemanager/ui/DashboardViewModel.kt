package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.model.HotspotInfo
import com.glassstorm.phonemanager.hub.HubStarter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Dashboard state holder.
 *
 * Every collaborator is a CONSTRUCTOR dependency. The two reads are typed as
 * DOMAIN ports; [hubStarter] is the app-owned seam the start AND stop actions
 * cross, so the ViewModel stays Android-free while the listener's lifetime is
 * owned by the foreground service. Under compile-time DI a missing collaborator
 * is impossible, so a `false` availability flag describes a port that is PRESENT
 * but FAILING (a listener that cannot report its state, an access point whose
 * driver throws): each read is wrapped, so the failure is a state transition
 * rather than a crash. Availability is TRI-STATE — `null` until the first read
 * resolves, `false` only for a resolved failure — so a cold start never flashes
 * a red error before the first snapshot arrives.
 *
 * ## Start is asynchronous
 *
 * [HubStarter.start] hands the job to the foreground service and returns
 * immediately, so the listener is NOT bound yet when the call returns. [onStartHub]
 * therefore starts a bounded poll that republishes the state once the service has
 * actually brought the hub up; without it the panel would show "stopped" forever.
 * A start that throws is surfaced as [DashboardUiState.startError] — never
 * swallowed.
 *
 * ## The connection card shows the DETECTED hotspot
 *
 * There is no programmatic hotspot: the hub adopts the user's manually-enabled
 * phone Mobile Hotspot. [refresh] reads [HotspotController.detectManualTether]
 * only; the reported `manual-tether` placeholder carries the discovered gateway
 * address and an empty passphrase (the user owns the real credentials). The hub
 * never creates, stops, or reconfigures that access point, so the dashboard has no
 * hotspot buttons.
 *
 * The detection walks the network interfaces, and [refresh] runs from
 * `viewModelScope`, so the blocking read is dispatched to [io] and only the
 * [uiState] write happens back on the main dispatcher.
 */
class DashboardViewModel
    private constructor(
        private val hub: HubServer,
        private val hotspot: HotspotController,
        private val hubStarter: HubStarter,
        private val pollIntervalMs: Long,
        private val io: CoroutineDispatcher,
        refreshOnInit: Boolean,
    ) : ViewModel() {
        @Inject
        constructor(
            hub: HubServer,
            hotspot: HotspotController,
            hubStarter: HubStarter,
        ) : this(hub, hotspot, hubStarter, DEFAULT_POLL_INTERVAL_MS, Dispatchers.IO, refreshOnInit = true)

        private val state = MutableStateFlow(DashboardUiState())

        val uiState: StateFlow<DashboardUiState> = state.asStateFlow()

        private var pollJob: Job? = null

        init {
            if (refreshOnInit) {
                viewModelScope.launch { refresh() }
            }
        }

        /**
         * Start the hub through its foreground service, then watch for it to come
         * up.
         *
         * The start is DELEGATED rather than performed here: [HubStarter] hands the
         * job to the foreground service, so the listener outlives this UI process.
         * Because that hand-off is asynchronous, [startPolling] republishes the
         * state once the service has bound the port. A failure to even request the
         * start (for example an Android background-start refusal) is reported in
         * [DashboardUiState.startError] instead of being discarded.
         */
        fun onStartHub() {
            val failure = runCatching { hubStarter.start() }.exceptionOrNull()
            viewModelScope.launch {
                refresh(startError = failure?.let { "Hub failed to start: ${it.message ?: "unknown"}" })
            }
            if (failure == null) startPolling()
        }

        /**
         * Stop the hub through the SAME foreground service that started it.
         *
         * Teardown must cross the service seam: the service owns [HubBringUp], which
         * runs the access point, the listener and discovery as one unit, so stopping
         * the listener behind its back would leave discovery advertising a dead port
         * and the service unable to start the hub again.
         */
        fun onStopHub() {
            cancelPolling()
            runCatching { hubStarter.stop() }
            viewModelScope.launch { refresh(startError = null) }
        }

        /**
         * Republish the state until the hub reports running, or the attempt budget
         * is spent. Bounded so a hub that never comes up cannot poll forever.
         */
        private fun startPolling() {
            if (pollJob?.isActive == true) return
            pollJob =
                viewModelScope.launch {
                    var remaining = MAX_START_POLLS
                    while (remaining-- > 0) {
                        delay(pollIntervalMs)
                        refresh()
                        if (state.value.running) break
                    }
                }
        }

        private fun cancelPolling() {
            pollJob?.cancel()
            pollJob = null
        }

        override fun onCleared() {
            cancelPolling()
            super.onCleared()
        }

        private suspend fun refresh(startError: String? = state.value.startError) {
            val running = runCatching { hub.isRunning() }
            val port = runCatching { hub.boundPort() }
            val detected = readHotspot()

            state.value =
                DashboardUiState(
                    hubAvailable = running.isSuccess && port.isSuccess,
                    hotspotAvailable = detected.isSuccess,
                    running = running.getOrDefault(false),
                    boundPort = port.getOrDefault(0),
                    hotspot = detected.getOrNull(),
                    startError = startError,
                )
        }

        /** The manually-enabled hotspot currently visible, or `null` when none is. */
        private suspend fun readHotspot(): Result<HotspotInfo?> =
            withContext(io) {
                runCatching { hotspot.detectManualTether() }
            }

        companion object {
            /** How often the dashboard re-reads the hub while waiting for start. */
            const val DEFAULT_POLL_INTERVAL_MS: Long = 250L

            /** Attempts before the post-start poll gives up (250 ms × 80 = 20 s). */
            const val MAX_START_POLLS: Int = 80

            /**
             * Test seam: builds the ViewModel with an explicit poll interval and
             * blocking-work dispatcher so the post-start cadence is deterministic and
             * the hotspot read never races the main thread. [refreshOnInit] can be
             * disabled to assert the pre-read `null` availability state.
             */
            fun forTesting(
                hub: HubServer,
                hotspot: HotspotController,
                hubStarter: HubStarter,
                pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
                io: CoroutineDispatcher = Dispatchers.IO,
                refreshOnInit: Boolean = true,
            ): DashboardViewModel = DashboardViewModel(hub, hotspot, hubStarter, pollIntervalMs, io, refreshOnInit)
        }
    }
