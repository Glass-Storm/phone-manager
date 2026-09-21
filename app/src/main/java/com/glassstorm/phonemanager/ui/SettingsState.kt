package com.glassstorm.phonemanager.ui

/**
 * Everything the Settings screen renders, derived from the domain `AppConfig`,
 * `HubServer` and `BatteryExemption` ports.
 *
 * [configAvailable] is `false` when no `AppConfig` was registered, which drives
 * the "not available" line instead of a crash.
 *
 * [apiKeyDraft] mirrors the reference client: the stored key is loaded into an
 * editable field so it can be replaced, but the field is rendered through a password
 * transformation while [apiKeyVisible] is `false`. Revealing it takes an explicit
 * action, so the cleartext key is never on screen by default.
 */
data class SettingsUiState(
    val configAvailable: Boolean = false,
    val sttEngine: String = "",
    val region: String = "",
    val hotspotMode: String = "",
    val apiKeyConfigured: Boolean = false,
    val apiKeyDraft: String = "",
    val apiKeyVisible: Boolean = false,
    val batteryExempt: Boolean = false,
    val batteryAvailable: Boolean = false,
    val protocol: String = "",
    val hubPort: Int = 0,
)
