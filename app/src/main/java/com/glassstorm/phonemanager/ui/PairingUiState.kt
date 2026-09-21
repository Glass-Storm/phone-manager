package com.glassstorm.phonemanager.ui

import com.glassstorm.phonemanager.domain.dto.Device

/**
 * Everything the Pairing screen renders, derived only from the `PairingService`
 * port.
 *
 * [pin] is non-null only while a window is open; the screen shows the PIN
 * prominently in that case and the idle text otherwise. [available] is `false`
 * when the port was never registered, which must degrade rather than crash.
 */
data class PairingUiState(
    val available: Boolean = false,
    val pin: String? = null,
    val expiresInSeconds: Long = 0,
    val devices: List<Device> = emptyList(),
)
