package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.network.HotspotFailure
import com.glassstorm.phonemanager.core.domain.network.HotspotUnavailableException
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.hub.HubStarter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dashboard state holder.
 *
 * Every collaborator is a CONSTRUCTOR dependency. The three reads are typed as
 * DOMAIN ports; [hubStarter] is the app-owned seam the start AND stop actions
 * cross, so the ViewModel stays Android-free while the listener's lifetime is
 * owned by the foreground service. Under compile-time DI a missing collaborator
 * is impossible, so the "not available" flags now describe a port that is PRESENT
 * but FAILING (a listener that cannot report its state, an access point whose
 * driver throws): each read is wrapped, so the failure is a state transition
 * rather than a crash.
 *
 * ## Start is asynchronous
 *
 * [HubStarter.start] hands the job to the foreground service and returns
 * immediately, so the listener is NOT bound yet when the call returns. [onStartHub]
 * therefore starts a bounded poll that republishes the state once the service has
 * actually brought the hub up; without it the panel would show "stopped" forever.
 * A start that throws is surfaced as [DashboardUiState.startError] — never
 * swallowed — mirroring the hotspot path.
 *
 * The domain ports are synchronous JVM APIs (the adapter does its own threading),
 * so the non-async actions stay synchronous: the UI state updates on the same
 * frame the user acted, which is also what makes the Robolectric tests
 * deterministic.
 */
class DashboardViewModel
    private constructor(
        private val hub: HubServer,
        private val hotspot: HotspotController,
        private val pairing: PairingService,
        private val hubStarter: HubStarter,
        private val pollIntervalMs: Long,
    ) : ViewModel() {
        @Inject
        constructor(
            hub: HubServer,
            hotspot: HotspotController,
            pairing: PairingService,
            hubStarter: HubStarter,
        ) : this(hub, hotspot, pairing, hubStarter, DEFAULT_POLL_INTERVAL_MS)

        private val state = MutableStateFlow(DashboardUiState())

        val uiState: StateFlow<DashboardUiState> = state.asStateFlow()

        private var pollJob: Job? = null

        init {
            refresh()
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
            refresh(startError = failure?.let { "Hub failed to start: ${it.message ?: "unknown"}" })
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
            refresh(startError = null)
        }

        /** Bring the access point up, surfacing the typed failure reason as text. */
        fun onStartHotspot() {
            val error = runCatching { hotspot.startHotspot() }.errorMessage()
            state.value = state.value.copy(hotspotError = error)
            refresh()
        }

        /** Tear the access point down. */
        fun onStopHotspot() {
            runCatching { hotspot.stopHotspot() }
            refresh()
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

        private fun refresh(startError: String? = state.value.startError) {
            val running = runCatching { hub.isRunning() }
            val port = runCatching { hub.boundPort() }
            val paired = runCatching { pairing.listPaired() }
            val detected = runCatching { hotspot.detectManualTether() }

            state.value =
                DashboardUiState(
                    hubAvailable = running.isSuccess && port.isSuccess,
                    hotspotAvailable = detected.isSuccess,
                    running = running.getOrDefault(false),
                    boundPort = port.getOrDefault(0),
                    pairedCount = paired.getOrDefault(emptyList()).size,
                    hotspot = detected.getOrNull(),
                    hotspotError = state.value.hotspotError,
                    startError = startError,
                )
        }

        companion object {
            /** How often the dashboard re-reads the hub while waiting for start. */
            const val DEFAULT_POLL_INTERVAL_MS: Long = 250L

            /** Attempts before the post-start poll gives up (250 ms × 80 = 20 s). */
            const val MAX_START_POLLS: Int = 80

            /**
             * Test seam: builds the ViewModel with an explicit poll interval so the
             * post-start cadence is deterministic. Production uses the `@Inject`
             * constructor.
             */
            fun forTesting(
                hub: HubServer,
                hotspot: HotspotController,
                pairing: PairingService,
                hubStarter: HubStarter,
                pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
            ): DashboardViewModel = DashboardViewModel(hub, hotspot, pairing, hubStarter, pollIntervalMs)
        }
    }

/** Render a failed hotspot start as the short reason line the screen shows. */
private fun Result<*>.errorMessage(): String? =
    exceptionOrNull()?.let { cause ->
        val reason =
            if (cause is HotspotUnavailableException) {
                cause.failure.reasonText()
            } else {
                cause.message ?: "unknown"
            }
        "Hotspot failed: $reason"
    }

/** The user-visible text for each typed hotspot failure. Exhaustive by design. */
private fun HotspotFailure.reasonText(): String =
    when (this) {
        is HotspotFailure.StartFailed -> reason
        HotspotFailure.PermissionDenied -> "permission-denied"
        HotspotFailure.LocationServicesDisabled -> "location-off"
        is HotspotFailure.IllegalTransition -> "illegal-transition"
    }
