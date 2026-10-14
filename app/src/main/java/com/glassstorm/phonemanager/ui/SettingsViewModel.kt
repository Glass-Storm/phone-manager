package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import com.glassstorm.phonemanager.battery.BatteryExemption
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.model.HotspotMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Settings state holder.
 *
 * The `AppConfig`, `HubServer` and `BatteryExemption` ports are CONSTRUCTOR
 * dependencies. A FAILING port degrades to the "not available" rendering instead
 * of a crash; a missing port is impossible under compile-time DI.
 *
 * Speech-to-text is not configurable: the hub runs a single on-device engine, so
 * this screen exposes only the hotspot mode, the Doze exemption and the endpoint.
 * The ports are synchronous JVM APIs, so every action is synchronous and the UI
 * updates on the frame the user acted.
 */
class SettingsViewModel
    @Inject
    constructor(
        private val config: AppConfig,
        private val hub: HubServer,
        private val battery: BatteryExemption,
    ) : ViewModel() {
        private val state = MutableStateFlow(SettingsUiState())

        val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

        init {
            refresh()
        }

        /** Persist the chosen hotspot mode and re-render it. */
        fun onSelectHotspotMode(mode: HotspotMode) {
            runCatching { config.setHotspotMode(mode) }
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
            val mode = runCatching { config.hotspotMode() }
            val exempt = runCatching { battery.isExempt() }
            val port = runCatching { hub.boundPort() }

            val configAvailable = mode.isSuccess
            state.value =
                SettingsUiState(
                    configAvailable = configAvailable,
                    hotspotMode = mode.getOrNull()?.label().orEmpty(),
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

private fun HotspotMode.label(): String =
    when (this) {
        HotspotMode.MANUAL -> "Manual"
        HotspotMode.AUTO -> "Auto"
    }
