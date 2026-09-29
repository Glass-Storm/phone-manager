package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Devices state holder.
 *
 * The `DeviceRepository` port is a CONSTRUCTOR dependency — `:app` never names a
 * concrete `:adapter` class — and it renders the rows the port returns.
 *
 * A FAILING port is rendered as unavailable: a database that cannot be read is no
 * more usable than one that was never wired, and neither may take the shell down.
 * The read is wrapped, so the failure is a state transition rather than a crash.
 *
 * The port is a synchronous JVM API (the adapter owns its threading), so the actions
 * stay synchronous and the UI updates on the frame the user acted.
 */
class DevicesViewModel
    @Inject
    constructor(
        private val repo: DeviceRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(DevicesUiState())

        val uiState: StateFlow<DevicesUiState> = state.asStateFlow()

        init {
            refresh()
        }

        /** Revoke [deviceId]: the row leaves the store and the list re-renders. */
        fun onRevoke(deviceId: String) {
            runCatching { repo.delete(deviceId) }
            refresh()
        }

        fun onRefresh() {
            refresh()
        }

        private fun refresh() {
            val rows = runCatching { repo.list() }
            state.value =
                rows.fold(
                    onSuccess = { DevicesUiState(available = true, devices = it) },
                    onFailure = { DevicesUiState(available = false) },
                )
        }
    }
