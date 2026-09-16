package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import com.glassstorm.phonemanager.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContextOrNull
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
class DashboardViewModel(private val GoContext: Context) : ViewModel() {

    private val GoHub: HubServer? = FromContextOrNull<HubServer>(GoContext)
    private val GoHotspot: HotspotController? = FromContextOrNull<HotspotController>(GoContext)
    private val GoPairing: PairingService? = FromContextOrNull<PairingService>(GoContext)

    private val GoState = MutableStateFlow(
        DashboardUiState(
            GoHubAvailable = GoHub != null,
            GoHotspotAvailable = GoHotspot != null,
        )
    )

    val GoUiState: StateFlow<DashboardUiState> = GoState.asStateFlow()

    init {
        GoRefresh()
    }

    /** Start the hub listener on an ephemeral port, then refresh the status panel. */
    fun GoOnStartHub() {
        GoHub?.GoStart(0)
        GoRefresh()
    }

    /** Stop the listener and the access point. Idempotent at the port level. */
    fun GoOnStopHub() {
        GoHub?.GoStop()
        GoHotspot?.GoStopHotspot()
        GoRefresh()
    }

    /** Bring the access point up, surfacing the typed failure reason as text. */
    fun GoOnStartHotspot() {
        val GoController = GoHotspot ?: return
        val GoError = runCatching { GoController.GoStartHotspot() }.GoErrorMessage()
        GoState.value = GoState.value.copy(GoHotspotError = GoError)
        GoRefresh()
    }

    /** Tear the access point down. */
    fun GoOnStopHotspot() {
        GoHotspot?.GoStopHotspot()
        GoRefresh()
    }

    private fun GoRefresh() {
        GoState.value = DashboardUiState(
            GoHubAvailable = GoHub != null,
            GoHotspotAvailable = GoHotspot != null,
            GoRunning = GoHub?.GoIsRunning() ?: false,
            GoBoundPort = GoHub?.GoBoundPort() ?: 0,
            GoPairedCount = GoPairing?.GoListPaired()?.size ?: 0,
            GoHotspot = GoHotspot?.GoDetectManualTether(),
            GoHotspotError = GoState.value.GoHotspotError,
        )
    }
}

/** Render a failed hotspot start as the short reason line the screen shows. */
private fun Result<*>.GoErrorMessage(): String? =
    exceptionOrNull()?.let { GoCause ->
        val GoReason =
            if (GoCause is HotspotUnavailableException) {
                GoCause.GoFailure.GoReasonText()
            } else {
                GoCause.message ?: "unknown"
            }
        "Hotspot failed: $GoReason"
    }

/** The user-visible text for each typed hotspot failure. Exhaustive by design. */
private fun HotspotFailure.GoReasonText(): String =
    when (this) {
        is HotspotFailure.StartFailed -> GoReason
        HotspotFailure.PermissionDenied -> "permission-denied"
        HotspotFailure.LocationServicesDisabled -> "location-off"
        is HotspotFailure.IllegalTransition -> "illegal-transition"
    }
