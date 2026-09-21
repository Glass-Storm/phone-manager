package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.domain.context.Context
import com.glassstorm.phonemanager.core.domain.context.fromContextOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Devices state holder.
 *
 * It resolves the `DeviceRepository` port by its DOMAIN interface type — never a
 * concrete `:adapter` class — and renders the rows the port returns.
 *
 * A missing port and a FAILING port are rendered identically as unavailable: a
 * database that cannot be read is no more usable than one that was never wired, and
 * neither may take the shell down. The read is wrapped, so the failure is a state
 * transition rather than a crash.
 *
 * The port is a synchronous JVM API (the adapter owns its threading), so the actions
 * stay synchronous and the UI updates on the frame the user acted.
 */
class DevicesViewModel(
    private val context: Context,
) : ViewModel() {
    private val repo: DeviceRepository? = fromContextOrNull<DeviceRepository>(context)

    private val state = MutableStateFlow(DevicesUiState())

    val uiState: StateFlow<DevicesUiState> = state.asStateFlow()

    init {
        refresh()
    }

    /** Revoke [deviceId]: the row leaves the store and the list re-renders. */
    fun onRevoke(deviceId: String) {
        runCatching { repo?.delete(deviceId) }
        refresh()
    }

    fun onRefresh() {
        refresh()
    }

    private fun refresh() {
        val port = repo
        if (port == null) {
            state.value = DevicesUiState(available = false)
            return
        }
        val rows = runCatching { port.list() }.getOrNull()
        state.value =
            if (rows == null) {
                DevicesUiState(available = false)
            } else {
                DevicesUiState(available = true, devices = rows)
            }
    }
}
