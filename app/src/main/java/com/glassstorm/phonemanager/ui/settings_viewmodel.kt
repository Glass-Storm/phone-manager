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
 * The stored key is loaded into [SettingsUiState.GoApiKeyDraft] so it can be
 * replaced, but [SettingsUiState.GoApiKeyVisible] is `false` until the user asks to
 * reveal it, and the screen renders the field through a password transformation in
 * that state. The key is therefore present for editing but unreadable on screen —
 * exactly the reference client's posture.
 *
 * The ports are synchronous JVM APIs, so every action is synchronous and the UI
 * updates on the frame the user acted.
 */
class SettingsViewModel(private val GoContext: Context) : ViewModel() {

    private val GoConfig: AppConfig? = FromContextOrNull<AppConfig>(GoContext)
    private val GoHub: HubServer? = FromContextOrNull<HubServer>(GoContext)
    private val GoBattery: BatteryExemption? = FromContextOrNull<BatteryExemption>(GoContext)

    private val GoState = MutableStateFlow(SettingsUiState())

    val GoUiState: StateFlow<SettingsUiState> = GoState.asStateFlow()

    init {
        GoRefresh()
    }

    /** Persist the chosen speech engine and re-render the selection. */
    fun GoOnSelectEngine(kind: SttEngine) {
        GoConfig?.GoSetSttEngine(kind)
        GoRefresh()
    }

    /** Persist the chosen region. An unsupported value is ignored by the port. */
    fun GoOnSelectRegion(region: String) {
        GoConfig?.GoSetRegion(region)
        GoRefresh()
    }

    /** Persist the chosen hotspot mode and re-render it. */
    fun GoOnSelectHotspotMode(mode: HotspotMode) {
        GoConfig?.GoSetHotspotMode(mode)
        GoRefresh()
    }

    /** Track the field's content without persisting it yet. */
    fun GoOnApiKeyDraftChanged(draft: String) {
        GoState.value = GoState.value.copy(GoApiKeyDraft = draft)
    }

    /** Persist the current draft; a blank draft clears the stored key. */
    fun GoOnSaveApiKey() {
        GoConfig?.GoSetApiKey(GoState.value.GoApiKeyDraft)
        GoRefresh()
    }

    /** Toggle whether the field renders the key in cleartext. Explicit, never default. */
    fun GoOnToggleApiKeyVisibility() {
        GoState.value = GoState.value.copy(GoApiKeyVisible = !GoState.value.GoApiKeyVisible)
    }

    /** Ask the platform for the Doze exemption. */
    fun GoOnRequestBatteryExemption() {
        GoBattery?.GoRequestExemption()
        GoRefresh()
    }

    fun GoOnRefresh() {
        GoRefresh()
    }

    private fun GoRefresh() {
        val GoPort = GoConfig
        if (GoPort == null) {
            GoState.value = SettingsUiState(GoConfigAvailable = false)
            return
        }
        GoState.value = SettingsUiState(
            GoConfigAvailable = true,
            GoSttEngine = GoPort.GoSttEngine().GoLabel(),
            GoRegion = GoPort.GoRegion(),
            GoHotspotMode = GoPort.GoHotspotMode().GoLabel(),
            GoApiKeyConfigured = GoPort.GoApiKey().isNotEmpty(),
            GoApiKeyDraft = GoPort.GoApiKey(),
            GoApiKeyVisible = GoState.value.GoApiKeyVisible,
            GoBatteryExempt = GoBattery?.GoIsExempt() ?: false,
            GoBatteryAvailable = GoBattery != null,
            GoProtocol = GO_PROTOCOL,
            GoHubPort = GoHub?.GoBoundPort() ?: 0,
        )
    }

    companion object {
        /** The frozen wire protocol the hub speaks. */
        const val GO_PROTOCOL: String = "ecosys.v1"
    }
}

private fun SttEngine.GoLabel(): String = when (this) {
    SttEngine.MOCK -> "Mock (offline)"
    SttEngine.SPEECHMATICS -> "Speechmatics"
}

private fun HotspotMode.GoLabel(): String = when (this) {
    HotspotMode.MANUAL -> "Manual"
    HotspotMode.AUTO -> "Auto"
}
