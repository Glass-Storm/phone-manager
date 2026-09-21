package com.glassstorm.phonemanager.ui

import com.glassstorm.phonemanager.domain.dto.Device

/**
 * Everything the Devices screen renders, derived only from the `DeviceRepository`
 * domain port.
 *
 * [available] is `false` when the port was not registered OR when its read threw
 * (a broken database): both are rendered as the same explicit "not available" line
 * so the shell never crashes on a half-wired or failing store.
 */
data class DevicesUiState(
    val available: Boolean = false,
    val devices: List<Device> = emptyList(),
)
