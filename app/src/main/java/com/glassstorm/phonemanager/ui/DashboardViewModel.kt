package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import com.glassstorm.phonemanager.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.fromContextOrNull
import com.glassstorm.phonemanager.domain.network.HotspotFailure
import com.glassstorm.phonemanager.domain.network.HotspotUnavailableException
import com.glassstorm.phonemanager.domain.service.PairingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dashboard state holder.
 *
 * It resolves every collaborator from the [Context] registry by the DOMAIN
 * interface type — never a concrete `:adapter` class — and keeps the ports that
 * are absent as `null`, which is what drives the "not available" rendering.
 *
 * There is no DI framework here on purpose: the composition root owns the
 * [Context] and hands it to the screen, which builds this ViewModel.
 *
 * The domain ports are synchronous JVM APIs (the adapter does its own threading),
 * so the actions below stay synchronous: no `viewModelScope` hop means the UI
 * state updates on the same frame the user acted, which is also what makes the
 * Robolectric tests deterministic.
 */
class DashboardViewModel(
    private val context: Context,
) : ViewModel() {
    private val hub: HubServer? = fromContextOrNull<HubServer>(context)
    private val hotspot: HotspotController? = fromContextOrNull<HotspotController>(context)
    private val pairing: PairingService? = fromContextOrNull<PairingService>(context)

    private val state =
        MutableStateFlow(
            DashboardUiState(
                hubAvailable = hub != null,
                hotspotAvailable = hotspot != null,
            ),
        )

    val uiState: StateFlow<DashboardUiState> = state.asStateFlow()

    init {
        refresh()
    }

    /** Start the hub listener on an ephemeral port, then refresh the status panel. */
    fun onStartHub() {
        hub?.start(0)
        refresh()
    }

    /** Stop the listener and the access point. Idempotent at the port level. */
    fun onStopHub() {
        hub?.stop()
        hotspot?.stopHotspot()
        refresh()
    }

    /** Bring the access point up, surfacing the typed failure reason as text. */
    fun onStartHotspot() {
        val controller = hotspot ?: return
        val error = runCatching { controller.startHotspot() }.errorMessage()
        state.value = state.value.copy(hotspotError = error)
        refresh()
    }

    /** Tear the access point down. */
    fun onStopHotspot() {
        hotspot?.stopHotspot()
        refresh()
    }

    private fun refresh() {
        state.value =
            DashboardUiState(
                hubAvailable = hub != null,
                hotspotAvailable = hotspot != null,
                running = hub?.isRunning() ?: false,
                boundPort = hub?.boundPort() ?: 0,
                pairedCount = pairing?.listPaired()?.size ?: 0,
                hotspot = hotspot?.detectManualTether(),
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
