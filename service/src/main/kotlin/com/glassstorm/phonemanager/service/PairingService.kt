package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContext
import com.glassstorm.phonemanager.domain.dto.Device
import com.glassstorm.phonemanager.domain.dto.PairOutcome
import com.glassstorm.phonemanager.domain.dto.Pairing
import com.glassstorm.phonemanager.domain.service.PairingService
import com.glassstorm.phonemanager.service.security.TokenCodec
import com.glassstorm.phonemanager.service.security.TokenVerifier
import java.security.SecureRandom

/**
 * Pairing + token authority.
 *
 * Resolves its [DeviceRepository] collaborator from the Context registry by the
 * domain INTERFACE type — never by a concrete adapter class, which `:service`
 * cannot even see (no build edge to `:adapter`).
 *
 * ## Window state is in-memory (documented)
 *
 * At most ONE pairing window exists at a time, held in this instance. The window
 * is a short-lived bootstrap secret (seconds to minutes), so it is deliberately
 * not persisted: a hub restart closes the window, which is the safe default.
 * Only the durable facts — paired devices and their token HASHES — reach the
 * repository. The plaintext token is minted, returned once, and never stored.
 *
 * ## Attempt cap
 *
 * [DeviceRepository] has no attempt API, so the failed-attempt counter lives here
 * alongside the window it guards. Once [PairingService.MAX_PIN_ATTEMPTS] failures
 * accumulate, the current PIN is locked; only a fresh window clears it. The
 * counter is PER-WINDOW and IN-MEMORY: a hub restart clears it. That is acceptable
 * because the window it guards is itself in-memory and short-lived — a restart
 * also closes the window, so there is nothing left to brute-force.
 *
 * ## Concurrency
 *
 * [pair] is the ONE unauthenticated RPC, and grpc-kotlin dispatches calls
 * concurrently on the server executor, so several `Pair` calls can be in flight at
 * once. All window state is therefore guarded: [openWindow], [stopWindow] and
 * [pair] are `@Synchronized` on this instance. That makes the single-use
 * check-and-consume and the failed-attempt increment each atomic. Without it two
 * callers holding the correct PIN could both pass the "not consumed" check and
 * mint two tokens from one single-use PIN, and concurrent bad PINs could lose
 * increments and let a brute-force slip past [PairingService.MAX_PIN_ATTEMPTS].
 *
 * @param clock now-provider, injectable so TTL/expiry are deterministic in tests.
 */
class PairingServiceImpl(
    private val ctx: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : PairingService,
    TokenVerifier {
    private val random = SecureRandom()

    private var window: Pairing? = null
    private var windowConsumed: Boolean = false
    private var failedAttempts: Int = 0

    private fun repo(): DeviceRepository = FromContext<DeviceRepository>(ctx)

    @Synchronized
    override fun openWindow(ttlMs: Long): Pairing {
        val fresh = Pairing(pin = TokenCodec.newPin(), expiresAtMs = clock() + ttlMs)
        window = fresh
        windowConsumed = false
        failedAttempts = 0
        return fresh
    }

    @Synchronized
    override fun stopWindow() {
        window = null
        windowConsumed = false
        failedAttempts = 0
    }

    @Synchronized
    override fun pair(
        pin: String,
        deviceName: String,
        role: String,
    ): PairOutcome {
        if (pin.isBlank()) return reject(PairOutcome.REASON_PIN_MISSING)
        if (deviceName.isBlank()) return reject(PairOutcome.REASON_NAME_MISSING)

        val current = window ?: return reject(PairOutcome.REASON_NO_WINDOW)
        if (clock() > current.expiresAtMs) return reject(PairOutcome.REASON_PIN_EXPIRED)
        if (windowConsumed) return reject(PairOutcome.REASON_PIN_CONSUMED)
        if (failedAttempts >= PairingService.MAX_PIN_ATTEMPTS) {
            return reject(PairOutcome.REASON_PIN_LOCKED)
        }

        // Constant-time PIN check — never `String.equals` on a secret.
        if (!TokenCodec.constantTimeEquals(current.pin, pin)) {
            failedAttempts += 1
            return reject(PairOutcome.REASON_PIN_INVALID)
        }

        // Success: single-use burn first, so a crash mid-issue cannot replay the PIN.
        windowConsumed = true
        failedAttempts = 0

        val salt = TokenCodec.newSalt()
        val token = TokenCodec.deriveToken(pin, salt, TokenCodec.DEFAULT_ITERATIONS)
        val deviceId = newDeviceId()
        repo().upsert(
            Device(
                deviceId = deviceId,
                deviceName = deviceName,
                role = role,
                tokenHash = TokenCodec.hashToken(token),
                pairedAtMs = clock(),
                lastSeenMs = null,
            ),
        )
        return PairOutcome.Ok(deviceId = deviceId, token = token)
    }

    override fun verifyToken(token: String): Device? {
        val hash = TokenCodec.hashToken(token)
        val device = repo().getByTokenHash(hash) ?: return null
        // Verified tokens double as proof of liveness.
        repo().touch(device.deviceId, clock())
        return device
    }

    override fun touchLastSeen(
        deviceId: String,
        seenAtMs: Long,
    ) {
        repo().touch(deviceId, seenAtMs)
    }

    override fun revoke(deviceId: String) {
        // Deleting the row removes the token hash, so the token stops verifying.
        repo().delete(deviceId)
    }

    override fun listPaired(): List<Device> = repo().list()

    private fun reject(reason: String): PairOutcome = PairOutcome.Rejected(reason = reason)

    private fun newDeviceId(): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
