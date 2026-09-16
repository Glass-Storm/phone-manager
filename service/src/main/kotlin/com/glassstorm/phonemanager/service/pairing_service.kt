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
 * alongside the window it guards. Once [PairingService.GoMaxPinAttempts] failures
 * accumulate, the current PIN is locked; only a fresh window clears it.
 *
 * @param GoClock now-provider, injectable so TTL/expiry are deterministic in tests.
 */
class PairingServiceImpl(
    private val GoCtx: Context,
    private val GoClock: () -> Long = { System.currentTimeMillis() },
) : PairingService, TokenVerifier {

    private val GoRandom = SecureRandom()

    private var GoWindow: Pairing? = null
    private var GoWindowConsumed: Boolean = false
    private var GoFailedAttempts: Int = 0

    private fun GoRepo(): DeviceRepository = FromContext<DeviceRepository>(GoCtx)

    override fun GoOpenWindow(ttlMs: Long): Pairing {
        val GoFresh = Pairing(GoPin = TokenCodec.GoNewPin(), GoExpiresAtMs = GoClock() + ttlMs)
        GoWindow = GoFresh
        GoWindowConsumed = false
        GoFailedAttempts = 0
        return GoFresh
    }

    override fun GoStopWindow() {
        GoWindow = null
        GoWindowConsumed = false
        GoFailedAttempts = 0
    }

    override fun GoPair(pin: String, deviceName: String, role: String): PairOutcome {
        if (pin.isBlank()) return GoReject(PairOutcome.GoReasonPinMissing)
        if (deviceName.isBlank()) return GoReject(PairOutcome.GoReasonNameMissing)

        val GoCurrent = GoWindow ?: return GoReject(PairOutcome.GoReasonNoWindow)
        if (GoClock() > GoCurrent.GoExpiresAtMs) return GoReject(PairOutcome.GoReasonPinExpired)
        if (GoWindowConsumed) return GoReject(PairOutcome.GoReasonPinConsumed)
        if (GoFailedAttempts >= PairingService.GoMaxPinAttempts) {
            return GoReject(PairOutcome.GoReasonPinLocked)
        }

        // Constant-time PIN check — never `String.equals` on a secret.
        if (!TokenCodec.GoConstantTimeEquals(GoCurrent.GoPin, pin)) {
            GoFailedAttempts += 1
            return GoReject(PairOutcome.GoReasonPinInvalid)
        }

        // Success: single-use burn first, so a crash mid-issue cannot replay the PIN.
        GoWindowConsumed = true
        GoFailedAttempts = 0

        val GoSalt = TokenCodec.GoNewSalt()
        val GoToken = TokenCodec.GoDeriveToken(pin, GoSalt, TokenCodec.GoDefaultIterations)
        val GoDeviceId = GoNewDeviceId()
        GoRepo().GoUpsert(
            Device(
                GoDeviceId = GoDeviceId,
                GoDeviceName = deviceName,
                GoRole = role,
                GoTokenHash = TokenCodec.GoHashToken(GoToken),
                GoPairedAtMs = GoClock(),
                GoLastSeenMs = null,
            )
        )
        return PairOutcome.GoOk(GoDeviceId = GoDeviceId, GoToken = GoToken)
    }

    override fun GoVerifyToken(token: String): Device? {
        val GoHash = TokenCodec.GoHashToken(token)
        val GoDevice = GoRepo().GoGetByTokenHash(GoHash) ?: return null
        // Verified tokens double as proof of liveness.
        GoRepo().GoTouch(GoDevice.GoDeviceId, GoClock())
        return GoDevice
    }

    override fun GoTouchLastSeen(deviceId: String, seenAtMs: Long) {
        GoRepo().GoTouch(deviceId, seenAtMs)
    }

    override fun GoRevoke(deviceId: String) {
        // Deleting the row removes the token hash, so the token stops verifying.
        GoRepo().GoDelete(deviceId)
    }

    override fun GoListPaired(): List<Device> = GoRepo().GoList()

    private fun GoReject(reason: String): PairOutcome = PairOutcome.GoRejected(GoReason = reason)

    private fun GoNewDeviceId(): String {
        val GoBytes = ByteArray(16).also { GoRandom.nextBytes(it) }
        return GoBytes.joinToString("") { "%02x".format(it) }
    }
}
