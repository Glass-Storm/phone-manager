package com.glassstorm.phonemanager.domain.adapter.network

import com.glassstorm.phonemanager.domain.dto.HotspotInfo

/**
 * Port (interface) for the phone-hosted access point.
 *
 * Owned by `:domain`; the Android implementation (LocalOnlyHotspot, with a
 * manual-tether fallback) lives in `:adapter` and is resolved through the
 * Context registry.
 *
 * Implementations MUST be safe to stop when never started (idempotent teardown)
 * and MUST never leak the underlying reservation.
 */
interface HotspotController {
    /**
     * Bring the access point up and return the credentials.
     *
     * @throws com.glassstorm.phonemanager.domain.network.HotspotUnavailableException
     *         when the AP cannot be started (missing permission, location services
     *         off, OEM refusal, timeout). The implementation MUST leave its state
     *         machine in `ERROR` — never `ACTIVE` — in that case.
     */
    fun GoStartHotspot(): HotspotInfo

    /** Tear the access point down. Idempotent: calling it when already stopped is a no-op. */
    fun GoStopHotspot()

    /** True only while the access point is fully up. */
    fun GoIsActive(): Boolean

    /**
     * Detect an already-active tether/access-point interface (e.g. the user enabled the
     * system hotspot manually because the OEM blocks programmatic LocalOnlyHotspot).
     *
     * Returns `null` when no tether interface is present. This is the PRIMARY
     * documented development path.
     */
    fun GoDetectManualTether(): HotspotInfo?
}
