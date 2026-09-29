package com.glassstorm.phonemanager.ui

import com.glassstorm.phonemanager.core.model.HotspotInfo

/**
 * Everything the Dashboard renders, derived only from domain ports.
 *
 * A `false` availability flag means the port's READ failed — under compile-time DI
 * a port cannot be absent, so the screen degrades to an explicit "not available"
 * line instead of crashing when a present adapter throws.
 */
data class DashboardUiState(
    val hubAvailable: Boolean = false,
    val hotspotAvailable: Boolean = false,
    val running: Boolean = false,
    val boundPort: Int = 0,
    val pairedCount: Int = 0,
    val hotspot: HotspotInfo? = null,
    val hotspotError: String? = null,
    val startError: String? = null,
)
