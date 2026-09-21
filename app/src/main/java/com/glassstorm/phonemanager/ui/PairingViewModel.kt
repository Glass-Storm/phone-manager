package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.core.model.Pairing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

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
 * The `PairingService` port is a CONSTRUCTOR dependency and a synchronous JVM API,
 * so actions stay synchronous and the UI state updates on the frame the user acted.
 * A FAILING port degrades to the "not available" rendering instead of a crash.
 */
class PairingViewModel
    private constructor(
        private val pairing: PairingService,
        private val nowMs: () -> Long,
    ) : ViewModel() {
        @Inject
        constructor(pairing: PairingService) : this(pairing, System::currentTimeMillis)

        private val state = MutableStateFlow(PairingUiState())

        val uiState: StateFlow<PairingUiState> = state.asStateFlow()

        init {
            refresh()
        }

        /** Open (or replace) the single pairing window and show its PIN. */
        fun onOpenWindow() {
            val window = runCatching { pairing.openWindow(WINDOW_TTL_MS) }
            if (window.isFailure) {
                state.value = state.value.copy(available = false)
                return
            }
            val opened = window.getOrThrow()
            state.value =
                state.value.copy(
                    available = true,
                    pin = opened.pin,
                    expiresInSeconds = opened.remainingSeconds(),
                )
            refresh()
        }

        /** Close the window immediately; the PIN stops being valid. */
        fun onCloseWindow() {
            runCatching { pairing.stopWindow() }
            state.value = state.value.copy(pin = null, expiresInSeconds = 0)
            refresh()
        }

        /** Revoke [deviceId]; its token stops verifying and it leaves the list. */
        fun onRevoke(deviceId: String) {
            runCatching { pairing.revoke(deviceId) }
            refresh()
        }

        fun onRefresh() {
            refresh()
        }

        private fun refresh() {
            val rows = runCatching { pairing.listPaired() }
            state.value =
                rows.fold(
                    onSuccess = { state.value.copy(available = true, devices = it) },
                    onFailure = { state.value.copy(available = false, devices = emptyList()) },
                )
        }

        private fun Pairing.remainingSeconds(): Long = ((expiresAtMs - nowMs()) / 1_000L).coerceAtLeast(0L)

        companion object {
            /** How long a pairing window stays open. Two minutes is the v1 default. */
            const val WINDOW_TTL_MS: Long = 120_000L
        }
    }
