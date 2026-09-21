package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContextOrNull
import com.glassstorm.phonemanager.domain.service.PairingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pairing state holder.
 *
 * The ViewModel owns only presentation state. The window, the PIN's single-use
 * semantics, the attempt cap and the revocation rules all live in
 * `PairingService` — this class never reimplements them, so there is exactly one
 * authority for pairing on the phone.
 *
 * ## One live PIN
 *
 * `PairingService.GoOpenWindow` replaces any existing window, so the UI stores
 * the LATEST returned PIN instead of accumulating windows. Reopening therefore
 * swaps the PIN rather than leaving two valid ones on screen.
 *
 * The `PairingService` port is a synchronous JVM API, so actions stay synchronous
 * and the UI state updates on the frame the user acted.
 */
class PairingViewModel(
    private val GoContext: Context,
    private val GoNowMs: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val GoPairing: PairingService? = FromContextOrNull<PairingService>(GoContext)

    private val GoState = MutableStateFlow(PairingUiState(GoAvailable = GoPairing != null))

    val GoUiState: StateFlow<PairingUiState> = GoState.asStateFlow()

    init {
        GoRefresh()
    }

    /** Open (or replace) the single pairing window and show its PIN. */
    fun GoOnOpenWindow() {
        val GoService = GoPairing ?: return
        // Replacing the previous window is the service's contract; storing only the
        // newest PIN is what guarantees a single live window in the UI.
        val GoWindow = GoService.GoOpenWindow(GoWindowTtlMs)
        GoState.value =
            GoState.value.copy(
                GoPin = GoWindow.GoPin,
                GoExpiresInSeconds = GoWindow.GoRemainingSeconds(),
            )
        GoRefresh()
    }

    /** Close the window immediately; the PIN stops being valid. */
    fun GoOnCloseWindow() {
        GoPairing?.GoStopWindow()
        GoState.value = GoState.value.copy(GoPin = null, GoExpiresInSeconds = 0)
        GoRefresh()
    }

    /** Revoke [deviceId]; its token stops verifying and it leaves the list. */
    fun GoOnRevoke(deviceId: String) {
        GoPairing?.GoRevoke(deviceId)
        GoRefresh()
    }

    fun GoOnRefresh() {
        GoRefresh()
    }

    private fun GoRefresh() {
        GoState.value =
            GoState.value.copy(
                GoAvailable = GoPairing != null,
                GoDevices = GoPairing?.GoListPaired() ?: emptyList(),
            )
    }

    private fun com.glassstorm.phonemanager.domain.dto.Pairing.GoRemainingSeconds(): Long =
        ((GoExpiresAtMs - GoNowMs()) / 1_000L).coerceAtLeast(0L)

    companion object {
        /** How long a pairing window stays open. Two minutes is the v1 default. */
        const val GoWindowTtlMs: Long = 120_000L
    }
}
