package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.fromContextOrNull
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
 * `PairingService.openWindow` replaces any existing window, so the UI stores
 * the LATEST returned PIN instead of accumulating windows. Reopening therefore
 * swaps the PIN rather than leaving two valid ones on screen.
 *
 * The `PairingService` port is a synchronous JVM API, so actions stay synchronous
 * and the UI state updates on the frame the user acted.
 */
class PairingViewModel(
    private val context: Context,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val pairing: PairingService? = fromContextOrNull<PairingService>(context)

    private val state = MutableStateFlow(PairingUiState(available = pairing != null))

    val uiState: StateFlow<PairingUiState> = state.asStateFlow()

    init {
        refresh()
    }

    /** Open (or replace) the single pairing window and show its PIN. */
    fun onOpenWindow() {
        val service = pairing ?: return
        // Replacing the previous window is the service's contract; storing only the
        // newest PIN is what guarantees a single live window in the UI.
        val window = service.openWindow(WINDOW_TTL_MS)
        state.value =
            state.value.copy(
                pin = window.pin,
                expiresInSeconds = window.remainingSeconds(),
            )
        refresh()
    }

    /** Close the window immediately; the PIN stops being valid. */
    fun onCloseWindow() {
        pairing?.stopWindow()
        state.value = state.value.copy(pin = null, expiresInSeconds = 0)
        refresh()
    }

    /** Revoke [deviceId]; its token stops verifying and it leaves the list. */
    fun onRevoke(deviceId: String) {
        pairing?.revoke(deviceId)
        refresh()
    }

    fun onRefresh() {
        refresh()
    }

    private fun refresh() {
        state.value =
            state.value.copy(
                available = pairing != null,
                devices = pairing?.listPaired() ?: emptyList(),
            )
    }

    private fun com.glassstorm.phonemanager.core.model.Pairing.remainingSeconds(): Long =
        ((expiresAtMs - nowMs()) / 1_000L).coerceAtLeast(0L)

    companion object {
        /** How long a pairing window stays open. Two minutes is the v1 default. */
        const val WINDOW_TTL_MS: Long = 120_000L
    }
}
