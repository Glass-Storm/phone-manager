package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import com.glassstorm.phonemanager.battery.BatteryExemption
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Settings state holder.
 *
 * The `HubServer` and `BatteryExemption` ports are CONSTRUCTOR dependencies. A
 * FAILING port degrades to the "not available" rendering instead of a crash; a
 * missing port is impossible under compile-time DI.
 *
 * The screen now exposes only what is still real: the Doze exemption and the
 * endpoint the hub serves. The hotspot mode selector is gone because there is no
 * programmatic hotspot, and the live relay diagnostics moved to the 即時 page.
 */
class SettingsViewModel
    @Inject
    constructor(
        private val hub: HubServer,
        private val battery: BatteryExemption,
    ) : ViewModel() {
        private val state = MutableStateFlow(SettingsUiState())

        val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

        init {
            refresh()
        }

        /** Ask the platform for the Doze exemption. */
        fun onRequestBatteryExemption() {
            runCatching { battery.requestExemption() }
            refresh()
        }

        fun onRefresh() {
            refresh()
        }

        private fun refresh() {
            val exempt = runCatching { battery.isExempt() }
            val port = runCatching { hub.boundPort() }

            state.value =
                SettingsUiState(
                    batteryExempt = exempt.getOrDefault(false),
                    batteryAvailable = exempt.isSuccess,
                    protocol = PROTOCOL,
                    hubPort = port.getOrDefault(0),
                )
        }

        companion object {
            /** The frozen wire protocol the hub speaks. */
            const val PROTOCOL: String = "ecosys.v1"
        }
    }
