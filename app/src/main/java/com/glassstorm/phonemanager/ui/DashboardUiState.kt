package com.glassstorm.phonemanager.ui

import com.glassstorm.phonemanager.core.model.HotspotInfo

/**
 * Everything the Dashboard renders, derived only from domain ports.
 *
 * ## Tri-state availability
 *
 * [hubAvailable] and [hotspotAvailable] are `null` until the FIRST read resolves.
 * That is deliberately distinct from a resolved failure (`false`): the page must
 * not flash a red `無法使用` while it is still reading, so `null` renders a quiet
 * `檢查中` placeholder and only `false` renders the error. Under compile-time DI a
 * port cannot be absent, so a `false` flag always means a PRESENT adapter threw.
 *
 * ## Hotspot vocabulary
 *
 * There is no programmatic hotspot: [hotspot] is the user's manually-enabled
 * Mobile Hotspot as detected, and only its gateway address is rendered. The
 * credentials belong to the system settings, not to this hub. The hotspot card
 * therefore says `熱點已開啟` / `熱點未開啟` — never `已連線`, which is reserved
 * for actual peers on the 即時 page, and only while the hub is running.
 */
data class DashboardUiState(
    val hubAvailable: Boolean? = null,
    val hotspotAvailable: Boolean? = null,
    val running: Boolean = false,
    val boundPort: Int = 0,
    val hotspot: HotspotInfo? = null,
    val startError: String? = null,
)
