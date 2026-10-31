package com.glassstorm.phonemanager.core.domain.adapter.network

import com.glassstorm.phonemanager.core.model.HotspotInfo

/**
 * Port (interface) for the phone-hosted access point.
 *
 * Owned by `:core:domain`; the Android implementation (a manual-tether-only
 * detector) lives in `:adapter` and is resolved through Dagger.
 *
 * The hub never creates an access point: it adopts the user's manually-enabled
 * phone Mobile Hotspot. Implementations MUST be safe to stop when never started
 * (idempotent teardown) and MUST never touch the user's own hotspot during
 * teardown.
 */
interface HotspotController {
    /**
     * Return the currently-detected access point credentials.
     *
     * On the manual-only implementation this is the same interface scan as
     * [detectManualTether]; it never creates an access point.
     *
     * @throws com.glassstorm.phonemanager.core.domain.network.HotspotUnavailableException
     *         when no manual hotspot is active.
     */
    fun startHotspot(): HotspotInfo

    /**
     * Tear the access point down. Idempotent: calling it when already stopped is a
     * no-op. A manual-tether implementation NEVER stops the user's hotspot.
     */
    fun stopHotspot()

    /** True only while a detected access point is present. */
    fun isActive(): Boolean

    /**
     * Detect an already-active tether/access-point interface (the user enabled the
     * system Mobile Hotspot by hand).
     *
     * Returns `null` when no tether interface is present. This is the ONLY
     * supported access-point path.
     */
    fun detectManualTether(): HotspotInfo?
}
