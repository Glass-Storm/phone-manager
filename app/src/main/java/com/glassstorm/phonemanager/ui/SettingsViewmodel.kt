package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import com.glassstorm.phonemanager.battery.BatteryExemption
import com.glassstorm.phonemanager.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContextOrNull
import com.glassstorm.phonemanager.domain.dto.HotspotMode
import com.glassstorm.phonemanager.domain.dto.SttEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings state holder.
 *
 * It resolves the `AppConfig`, `HubServer` and `BatteryExemption` ports from the
 * [Context] by their DOMAIN interface types and keeps an absent port as `null`,
 * which drives the "not available" rendering instead of a crash.
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
class SettingsViewModel(
    private val context: Context,
) : ViewModel() {
    private val config: AppConfig? = FromContextOrNull<AppConfig>(context)
    private val hub: HubServer? = FromContextOrNull<HubServer>(context)
    private val battery: BatteryExemption? = FromContextOrNull<BatteryExemption>(context)

    private val state = MutableStateFlow(SettingsUiState())

    val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

    init {
        refresh()
    }

    /** Persist the chosen speech engine and re-render the selection. */
    fun onSelectEngine(kind: SttEngine) {
        config?.setSttEngine(kind)
        refresh()
    }

    /** Persist the chosen region. An unsupported value is ignored by the port. */
    fun onSelectRegion(region: String) {
        config?.setRegion(region)
        refresh()
    }

    /** Persist the chosen hotspot mode and re-render it. */
    fun onSelectHotspotMode(mode: HotspotMode) {
        config?.setHotspotMode(mode)
        refresh()
    }

    /** Track the field's content without persisting it yet. */
    fun onApiKeyDraftChanged(draft: String) {
        state.value = state.value.copy(apiKeyDraft = draft)
    }

    /** Persist the current draft; a blank draft clears the stored key. */
    fun onSaveApiKey() {
        config?.setApiKey(state.value.apiKeyDraft)
        refresh()
    }

    /** Toggle whether the field renders the key in cleartext. Explicit, never default. */
    fun onToggleApiKeyVisibility() {
        state.value = state.value.copy(apiKeyVisible = !state.value.apiKeyVisible)
    }

    /** Ask the platform for the Doze exemption. */
    fun onRequestBatteryExemption() {
        battery?.requestExemption()
        refresh()
    }

    fun onRefresh() {
        refresh()
    }

    private fun refresh() {
        val port = config
        if (port == null) {
            state.value = SettingsUiState(configAvailable = false)
            return
        }
        state.value =
            SettingsUiState(
                configAvailable = true,
                sttEngine = port.sttEngine().label(),
                region = port.region(),
                hotspotMode = port.hotspotMode().label(),
                apiKeyConfigured = port.apiKey().isNotEmpty(),
                apiKeyDraft = port.apiKey(),
                apiKeyVisible = state.value.apiKeyVisible,
                batteryExempt = battery?.isExempt() ?: false,
                batteryAvailable = battery != null,
                protocol = GO_PROTOCOL,
                hubPort = hub?.boundPort() ?: 0,
            )
    }

    companion object {
        /** The frozen wire protocol the hub speaks. */
        const val GO_PROTOCOL: String = "ecosys.v1"
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
