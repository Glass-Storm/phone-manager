package com.glassstorm.phonemanager.ui

import com.glassstorm.phonemanager.domain.dto.HotspotInfo

/**
 * Everything the Dashboard renders, derived only from domain ports.
 *
 * A `false` availability flag means the port was not registered in the Context —
 * the app shell test composes a Context with almost nothing in it, so the screen
 * must degrade to an explicit "not available" line instead of crashing.
 */
data class DashboardUiState(
    val GoHubAvailable: Boolean = false,
    val GoHotspotAvailable: Boolean = false,
    val GoRunning: Boolean = false,
    val GoBoundPort: Int = 0,
    val GoPairedCount: Int = 0,
    val GoHotspot: HotspotInfo? = null,
    val GoHotspotError: String? = null,
)
