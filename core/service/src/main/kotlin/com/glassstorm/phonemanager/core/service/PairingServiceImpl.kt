package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.domain.security.TokenVerifier
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.core.model.Device
import com.glassstorm.phonemanager.core.model.PairOutcome
import com.glassstorm.phonemanager.core.model.Pairing
import com.glassstorm.phonemanager.core.service.security.TokenCodec
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pairing + token authority.
 *
 * The [DeviceRepository] collaborator is a CONSTRUCTOR dependency. `:service` has
 * no build edge to `:adapter`, so the concrete adapter is unknowable here by
 * construction.
 *
 * ## Single object for two ports
 *
 * This class implements BOTH [PairingService] and [TokenVerifier]. The gRPC
 * Pairing service and the `AuthInterceptor` must observe the SAME pairing state,
 * or the interceptor could verify against a different authority than the one
 * `Pair` mints tokens into. Dagger binds both ports to one `@Singleton` instance
 * (see `ServiceModule`), exactly as the registry registered this object twice.
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
 */
@Singleton
class PairingServiceImpl
    private constructor(
        private val repository: DeviceRepository,
        private val clock: () -> Long,
    ) : PairingService,
        TokenVerifier {
        @Inject
        constructor(repository: DeviceRepository) : this(repository, System::currentTimeMillis)

        private val random = SecureRandom()

        private var window: Pairing? = null
        private var windowConsumed: Boolean = false
        private var failedAttempts: Int = 0

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
            repository.upsert(
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
            val device = repository.getByTokenHash(hash) ?: return null
            // Verified tokens double as proof of liveness.
            repository.touch(device.deviceId, clock())
            return device
        }

        override fun touchLastSeen(
            deviceId: String,
            seenAtMs: Long,
        ) {
            repository.touch(deviceId, seenAtMs)
        }

        override fun revoke(deviceId: String) {
            // Deleting the row removes the token hash, so the token stops verifying.
            repository.delete(deviceId)
        }

        override fun listPaired(): List<Device> = repository.list()

        private fun reject(reason: String): PairOutcome = PairOutcome.Rejected(reason = reason)

        private fun newDeviceId(): String {
            val bytes = ByteArray(16).also { random.nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        companion object {
            /**
             * Test seam: builds the service against a deterministic clock so TTL and
             * expiry are exact. Production always uses the `@Inject` constructor,
             * which binds [System.currentTimeMillis].
             */
            fun withClock(
                repository: DeviceRepository,
                clock: () -> Long,
            ): PairingServiceImpl = PairingServiceImpl(repository, clock)
        }
    }
