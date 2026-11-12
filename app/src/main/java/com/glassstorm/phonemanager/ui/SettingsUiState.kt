package com.glassstorm.phonemanager.ui

/**
 * The enrollment dialog's state machine, one value per visible outcome.
 *
 * [Idle] renders the form, [Recording] disables it and shows progress, [Enrolled]
 * confirms the stored voiceprint, and [Rejected] pairs with [SettingsUiState.enrollReason]
 * so the screen can explain the machine-readable reason in plain language.
 */
enum class EnrollmentPhase {
    Idle,
    Recording,
    Enrolled,
    Rejected,
}

/**
 * Everything the Settings screen renders, derived from the domain `HubServer`,
 * `BatteryExemption`, `EnrollmentStore` and `VoiceCapturePort` ports.
 *
 * [batteryAvailable] is `false` when the battery read fails, which drives the
 * "not available" line instead of a crash. [enrollReason] carries the
 * machine-readable rejection code (never a sentence); the screen owns the wording.
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
    val enrollName: String = "",
    val enrollPhase: EnrollmentPhase = EnrollmentPhase.Idle,
    val enrollReason: String? = null,
    val enrolledNames: List<String> = emptyList(),
    val enrollDeviceBacked: Boolean = false,
)
