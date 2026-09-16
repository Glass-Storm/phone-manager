package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContextOrNull
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
    private val GoContext: Context,
) : ViewModel() {
    private val GoRepo: DeviceRepository? = FromContextOrNull<DeviceRepository>(GoContext)

    private val GoState = MutableStateFlow(DevicesUiState())

    val GoUiState: StateFlow<DevicesUiState> = GoState.asStateFlow()

    init {
        GoRefresh()
    }

    /** Revoke [deviceId]: the row leaves the store and the list re-renders. */
    fun GoOnRevoke(deviceId: String) {
        runCatching { GoRepo?.GoDelete(deviceId) }
        GoRefresh()
    }

    fun GoOnRefresh() {
        GoRefresh()
    }

    private fun GoRefresh() {
        val GoPort = GoRepo
        if (GoPort == null) {
            GoState.value = DevicesUiState(GoAvailable = false)
            return
        }
        val GoRows = runCatching { GoPort.GoList() }.getOrNull()
        GoState.value =
            if (GoRows == null) {
                DevicesUiState(GoAvailable = false)
            } else {
                DevicesUiState(GoAvailable = true, GoDevices = GoRows)
            }
    }
}
