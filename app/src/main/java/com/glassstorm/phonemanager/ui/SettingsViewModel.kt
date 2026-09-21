package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import com.glassstorm.phonemanager.battery.BatteryExemption
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.model.HotspotMode
import com.glassstorm.phonemanager.core.model.SttEngine
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
 * ## The API key never reaches the screen in cleartext by default
 *
 * The stored key is loaded into [SettingsUiState.apiKeyDraft] so it can be
 * replaced, but [SettingsUiState.apiKeyVisible] is `false` until the user asks to
 * reveal it, and the screen renders the field through a password transformation in
 * that state. The key is therefore present for editing but unreadable on screen —
 * exactly the reference client's posture.
 *
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

        /** Persist the chosen speech engine and re-render the selection. */
        fun onSelectEngine(kind: SttEngine) {
            runCatching { config.setSttEngine(kind) }
            refresh()
        }

        /** Persist the chosen region. An unsupported value is ignored by the port. */
        fun onSelectRegion(region: String) {
            runCatching { config.setRegion(region) }
            refresh()
        }

        /** Persist the chosen hotspot mode and re-render it. */
        fun onSelectHotspotMode(mode: HotspotMode) {
            runCatching { config.setHotspotMode(mode) }
            refresh()
        }

        /** Track the field's content without persisting it yet. */
        fun onApiKeyDraftChanged(draft: String) {
            state.value = state.value.copy(apiKeyDraft = draft)
        }

        /** Persist the current draft; a blank draft clears the stored key. */
        fun onSaveApiKey() {
            runCatching { config.setApiKey(state.value.apiKeyDraft) }
            refresh()
        }

        /** Toggle whether the field renders the key in cleartext. Explicit, never default. */
        fun onToggleApiKeyVisibility() {
            state.value = state.value.copy(apiKeyVisible = !state.value.apiKeyVisible)
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
            val engine = runCatching { config.sttEngine() }
            val region = runCatching { config.region() }
            val mode = runCatching { config.hotspotMode() }
            val key = runCatching { config.apiKey() }
            val exempt = runCatching { battery.isExempt() }
            val port = runCatching { hub.boundPort() }

            val configAvailable = engine.isSuccess && region.isSuccess && mode.isSuccess && key.isSuccess
            state.value =
                SettingsUiState(
                    configAvailable = configAvailable,
                    sttEngine = engine.getOrNull()?.label().orEmpty(),
                    region = region.getOrDefault(""),
                    hotspotMode = mode.getOrNull()?.label().orEmpty(),
                    apiKeyConfigured = key.getOrDefault("").isNotEmpty(),
                    apiKeyDraft = key.getOrDefault(""),
                    apiKeyVisible = state.value.apiKeyVisible,
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

private fun SttEngine.label(): String =
    when (this) {
        SttEngine.MOCK -> "Mock (offline)"
        SttEngine.SPEECHMATICS -> "Speechmatics"
    }

private fun HotspotMode.label(): String =
    when (this) {
        HotspotMode.MANUAL -> "Manual"
        HotspotMode.AUTO -> "Auto"
    }
