package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.domain.security.TokenVerifier
import com.glassstorm.phonemanager.core.model.Device
import com.glassstorm.phonemanager.core.service.security.TokenCodec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Token authority: token↔device operations backed by the [DeviceRepository].
 *
 * This service owns ONE responsibility — resolving a bearer token to the device
 * that owns it and mutating last-seen/revocation. It holds NO pairing-window
 * state; that belongs to [PairingWindowService]. The [DeviceRepository]
 * collaborator is a CONSTRUCTOR dependency: `:service` has no build edge to
 * `:adapter`, so the concrete adapter is unknowable here by construction.
 */
@Singleton
class TokenAuthorityService
    private constructor(
        private val repository: DeviceRepository,
        private val clock: () -> Long,
    ) : TokenVerifier {
        @Inject
        constructor(repository: DeviceRepository) : this(repository, System::currentTimeMillis)

        /**
         * Resolve [token] to its device AND bump its last-seen instant.
         *
         * Returns `null` when the token is unknown, tampered, or belongs to a
         * revoked device.
         */
        override fun verifyToken(token: String): Device? {
            val device = repository.getByTokenHash(TokenCodec.hashToken(token)) ?: return null
            // Verified tokens double as proof of liveness.
            repository.touch(device.deviceId, clock())
            return device
        }

        /** Record that [deviceId] was seen at [seenAtMs] without re-deriving its token. */
        fun touchLastSeen(
            deviceId: String,
            seenAtMs: Long,
        ) {
            repository.touch(deviceId, seenAtMs)
        }

        /** Revoke [deviceId]: deleting the row removes its token hash. Idempotent. */
        fun revoke(deviceId: String) {
            repository.delete(deviceId)
        }

        /** Every currently paired device. */
        fun listPaired(): List<Device> = repository.list()

        companion object {
            /** Test seam: builds the authority against a deterministic clock. */
            fun withClock(
                repository: DeviceRepository,
                clock: () -> Long,
            ): TokenAuthorityService = TokenAuthorityService(repository, clock)
        }
    }
