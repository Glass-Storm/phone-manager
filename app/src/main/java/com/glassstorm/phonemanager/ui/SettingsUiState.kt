package com.glassstorm.phonemanager.ui

/**
 * Everything the Settings screen renders, derived from the domain `AppConfig`,
 * `HubServer` and `BatteryExemption` ports.
 *
 * [configAvailable] is `false` when no `AppConfig` was registered, which drives
 * the "not available" line instead of a crash.
 *
 * Speech-to-text is not configurable, so this state carries no engine, region or
 * credential field: the hub runs a single on-device engine with no cloud path.
 */
data class SettingsUiState(
    val configAvailable: Boolean = false,
    val hotspotMode: String = "",
    val batteryExempt: Boolean = false,
    val batteryAvailable: Boolean = false,
    val protocol: String = "",
    val hubPort: Int = 0,
)
