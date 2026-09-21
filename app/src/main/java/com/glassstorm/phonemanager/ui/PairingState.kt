package com.glassstorm.phonemanager.ui

import com.glassstorm.phonemanager.domain.dto.Device

/**
 * Everything the Pairing screen renders, derived only from the `PairingService`
 * port.
 *
 * [GoPin] is non-null only while a window is open; the screen shows the PIN
 * prominently in that case and the idle text otherwise. [GoAvailable] is `false`
 * when the port was never registered, which must degrade rather than crash.
 */
data class PairingUiState(
    val GoAvailable: Boolean = false,
    val GoPin: String? = null,
    val GoExpiresInSeconds: Long = 0,
    val GoDevices: List<Device> = emptyList(),
)
