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
 * Pairing orchestrator: mints a device + token once a window ACCEPTS a PIN.
 *
 * This class composes two focused collaborators and owns no state of its own:
 *
 *  * [PairingWindowService] owns the window, the attempt cap, and the pair DECISION;
 *  * [TokenAuthorityService] owns token↔device operations against [DeviceRepository].
 *
 * ## Single object for two ports
 *
 * This class implements BOTH [PairingService] and [TokenVerifier]. The gRPC
 * Pairing service and the `AuthInterceptor` must observe the SAME pairing state,
 * or the interceptor could verify against a different authority than the one
 * `Pair` mints tokens into. Dagger binds both ports to one `@Singleton` instance
 * (see `ServiceModule`).
 *
 * ## Ordering
 *
 * [pair] asks [PairingWindowService.decide] for a verdict FIRST, and only on
 * [PairWindowDecision.Accepted] — which has already burned the single-use PIN —
 * does it derive the token and persist the device. A rejection never reaches the
 * repository and never mints a token. The [DeviceRepository] collaborator is a
 * CONSTRUCTOR dependency: `:service` has no build edge to `:adapter`.
 */
@Singleton
class PairingServiceImpl
    private constructor(
        private val windows: PairingWindowService,
        private val tokens: TokenAuthorityService,
        private val repository: DeviceRepository,
        private val clock: () -> Long,
    ) : PairingService,
        TokenVerifier {
        @Inject
        constructor(
            windows: PairingWindowService,
            tokens: TokenAuthorityService,
            repository: DeviceRepository,
        ) : this(windows, tokens, repository, System::currentTimeMillis)

        private val random = SecureRandom()

        override fun openWindow(ttlMs: Long): Pairing = windows.openWindow(ttlMs)

        override fun stopWindow() = windows.stopWindow()

        override fun pair(
            pin: String,
            deviceName: String,
            role: String,
        ): PairOutcome {
            when (val decision = windows.decide(pin, deviceName)) {
                is PairWindowDecision.Rejected -> return PairOutcome.Rejected(decision.reason)
                PairWindowDecision.Accepted -> {
                    // The window burned the PIN before returning Accepted, so this
                    // mint-and-persist cannot replay even if it crashes midway.
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
            }
        }

        /**
         * Resolve [token] to its device AND bump its last-seen instant.
         *
         * Kept as a concrete method because tests call it on this concrete type;
         * the work is delegated to [TokenAuthorityService].
         */
        override fun verifyToken(token: String): Device? = tokens.verifyToken(token)

        override fun touchLastSeen(
            deviceId: String,
            seenAtMs: Long,
        ) = tokens.touchLastSeen(deviceId, seenAtMs)

        override fun revoke(deviceId: String) = tokens.revoke(deviceId)

        override fun listPaired(): List<Device> = tokens.listPaired()

        private fun newDeviceId(): String {
            val bytes = ByteArray(16).also { random.nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        companion object {
            /**
             * Test seam: builds the orchestrator over deterministic collaborators so
             * TTL and expiry are exact. Production uses the `@Inject` constructor.
             */
            fun withClock(
                repository: DeviceRepository,
                clock: () -> Long,
            ): PairingServiceImpl =
                PairingServiceImpl(
                    PairingWindowService.withClock(clock),
                    TokenAuthorityService.withClock(repository, clock),
                    repository,
                    clock,
                )
        }
    }
