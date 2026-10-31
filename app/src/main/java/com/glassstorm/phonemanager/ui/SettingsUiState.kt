package com.glassstorm.phonemanager.ui

/**
 * Everything the Settings screen renders, derived from the domain `HubServer` and
 * `BatteryExemption` ports.
 *
 * [batteryAvailable] is `false` when the battery read fails, which drives the
 * "not available" line instead of a crash.
 *
 * The hotspot mode is gone with the programmatic hotspot: the user enables the
 * system Mobile Hotspot by hand, so there is nothing for this screen to configure
 * there. Speech-to-text is not configurable either: the hub runs a single
 * on-device engine with no cloud path.
 */
data class SettingsUiState(
    val batteryExempt: Boolean = false,
    val batteryAvailable: Boolean = false,
    val protocol: String = "",
    val hubPort: Int = 0,
)
