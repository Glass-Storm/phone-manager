package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.network.HotspotFailure
import com.glassstorm.phonemanager.core.domain.network.HotspotUnavailableException
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.hub.HubStarter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Dashboard state holder.
 *
 * Every collaborator is a CONSTRUCTOR dependency. The three reads are typed as
 * DOMAIN ports; [hubStarter] is the app-owned seam the start action crosses, so
 * the ViewModel stays Android-free while the listener's lifetime is owned by the
 * foreground service. Under compile-time DI a missing collaborator is impossible,
 * so the "not available" flags now describe a port that is PRESENT but FAILING (a
 * listener that cannot report its state, an access point whose driver throws):
 * each read is wrapped, so the failure is a state transition rather than a crash.
 *
 * The domain ports are synchronous JVM APIs (the adapter does its own threading),
 * so the actions below stay synchronous: no `viewModelScope` hop means the UI
 * state updates on the same frame the user acted, which is also what makes the
 * Robolectric tests deterministic.
 */
class DashboardViewModel
    @Inject
    constructor(
        private val hub: HubServer,
        private val hotspot: HotspotController,
        private val pairing: PairingService,
        private val hubStarter: HubStarter,
    ) : ViewModel() {
        private val state = MutableStateFlow(DashboardUiState())

        val uiState: StateFlow<DashboardUiState> = state.asStateFlow()

        init {
            refresh()
        }

        /**
         * Start the hub through its foreground service, then refresh the status
         * panel.
         *
         * The start is DELEGATED rather than performed here: [HubStarter] hands the
         * job to the foreground service, so the listener outlives this UI process.
         * The panel still reports the bound port because both share the one
         * process-wide [HubServer] singleton.
         */
        fun onStartHub() {
            runCatching { hubStarter.start() }
            refresh()
        }

        /** Stop the listener and the access point. Idempotent at the port level. */
        fun onStopHub() {
            runCatching { hub.stop() }
            runCatching { hotspot.stopHotspot() }
            refresh()
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

        private fun refresh() {
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
                )
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
