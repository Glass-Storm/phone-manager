package com.glassstorm.phonemanager.ui

/**
 * Everything the Settings screen renders, derived from the domain `AppConfig`,
 * `HubServer` and `BatteryExemption` ports.
 *
 * [GoConfigAvailable] is `false` when no `AppConfig` was registered, which drives
 * the "not available" line instead of a crash.
 *
 * [GoApiKeyDraft] mirrors the reference client: the stored key is loaded into an
 * editable field so it can be replaced, but the field is rendered through a password
 * transformation while [GoApiKeyVisible] is `false`. Revealing it takes an explicit
 * action, so the cleartext key is never on screen by default.
 */
data class SettingsUiState(
    val GoConfigAvailable: Boolean = false,
    val GoSttEngine: String = "",
    val GoRegion: String = "",
    val GoHotspotMode: String = "",
    val GoApiKeyConfigured: Boolean = false,
    val GoApiKeyDraft: String = "",
    val GoApiKeyVisible: Boolean = false,
    val GoBatteryExempt: Boolean = false,
    val GoBatteryAvailable: Boolean = false,
    val GoProtocol: String = "",
    val GoHubPort: Int = 0,
)
