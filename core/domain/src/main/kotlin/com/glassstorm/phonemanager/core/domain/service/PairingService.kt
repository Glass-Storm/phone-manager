package com.glassstorm.phonemanager.core.domain.service

import com.glassstorm.phonemanager.core.model.Device
import com.glassstorm.phonemanager.core.model.PairOutcome
import com.glassstorm.phonemanager.core.model.Pairing

/**
 * Pairing + token authority use-cases.
 *
 * Port (interface) owned by `:core:domain`; implemented in `:core:service`, which
 * receives its `DeviceRepository` collaborator by constructor injection.
 *
 * Security contract every implementation MUST honour:
 *  * a PIN is single-use and dies with its window (TTL) or its first success;
 *  * a rejected attempt never persists a device and never mints a token;
 *  * after `MAX_PIN_ATTEMPTS` failures the current PIN is locked until a fresh window;
 *  * only the token HASH is ever persisted — the plaintext token is returned once.
 */
interface PairingService {
    /** Open a single-use pairing window of [ttlMs], returning the freshly drawn [Pairing]. */
    fun openWindow(ttlMs: Long): Pairing

    /** Close the current window immediately. Idempotent when no window is open. */
    fun stopWindow()

    /**
     * Redeem [pin] for a new pairing.
     *
     * Returns [PairOutcome.Ok] with the device id and the one-time token, or a
     * typed [PairOutcome.Rejected] carrying a `PairOutcome.reason*` value.
     */
    fun pair(
        pin: String,
        deviceName: String,
        role: String,
    ): PairOutcome

    /**
     * Record that [deviceId] was seen at [seenAtMs] without re-deriving its token.
     */
    fun touchLastSeen(
        deviceId: String,
        seenAtMs: Long,
    )

    /** Revoke [deviceId]: its token stops verifying immediately. Idempotent. */
    fun revoke(deviceId: String)

    /** Every currently paired device. */
    fun listPaired(): List<Device>

    companion object {
        /** Failed attempts against one PIN before it locks out. */
        const val MAX_PIN_ATTEMPTS: Int = 5
    }
}
