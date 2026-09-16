package com.glassstorm.phonemanager.domain.service

import com.glassstorm.phonemanager.domain.dto.Device
import com.glassstorm.phonemanager.domain.dto.PairOutcome
import com.glassstorm.phonemanager.domain.dto.Pairing

/**
 * Pairing + token authority use-cases.
 *
 * Port (interface) owned by `:domain`; implemented in `:service`, which resolves
 * its `DeviceRepository` collaborator through the Context registry.
 *
 * Security contract every implementation MUST honour:
 *  * a PIN is single-use and dies with its window (TTL) or its first success;
 *  * a rejected attempt never persists a device and never mints a token;
 *  * after `GoMaxPinAttempts` failures the current PIN is locked until a fresh window;
 *  * only the token HASH is ever persisted — the plaintext token is returned once.
 */
interface PairingService {
    /** Open a single-use pairing window of [ttlMs], returning the freshly drawn [Pairing]. */
    fun GoOpenWindow(ttlMs: Long): Pairing

    /** Close the current window immediately. Idempotent when no window is open. */
    fun GoStopWindow()

    /**
     * Redeem [pin] for a new pairing.
     *
     * Returns [PairOutcome.GoOk] with the device id and the one-time token, or a
     * typed [PairOutcome.GoRejected] carrying a `PairOutcome.GoReason*` value.
     */
    fun GoPair(pin: String, deviceName: String, role: String): PairOutcome

    /**
     * Resolve the device owning [token] AND bump its last-seen instant.
     *
     * Returns `null` when the token is unknown, tampered, or belongs to a revoked
     * device.
     */
    fun GoVerifyToken(token: String): Device?

    /** Record that [deviceId] was seen at [seenAtMs] without re-deriving its token. */
    fun GoTouchLastSeen(deviceId: String, seenAtMs: Long)

    /** Revoke [deviceId]: its token stops verifying immediately. Idempotent. */
    fun GoRevoke(deviceId: String)

    /** Every currently paired device. */
    fun GoListPaired(): List<Device>

    companion object {
        /** Failed attempts against one PIN before it locks out. */
        const val GoMaxPinAttempts: Int = 5
    }
}
