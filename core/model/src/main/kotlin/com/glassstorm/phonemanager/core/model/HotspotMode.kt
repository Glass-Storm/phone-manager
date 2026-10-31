package com.glassstorm.phonemanager.core.model

/**
 * How the hub should treat the access point.
 *
 * [MANUAL] leaves the tethered hotspot to the user (the documented primary dev
 * path, since OEMs frequently block programmatic LocalOnlyHotspot); [AUTO] asks
 * the hub to bring its own LocalOnlyHotspot up.
 */
enum class HotspotMode {
    /** The user owns the access point; the hub detects and adopts it. */
    MANUAL,

    /** The hub starts its own LocalOnlyHotspot. */
    AUTO,
}
