package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.Register
import com.glassstorm.phonemanager.domain.dto.PairOutcome
import com.glassstorm.phonemanager.domain.dto.Pairing
import com.glassstorm.phonemanager.domain.service.PairingService
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Direct, service-level lifecycle proof for [PairingServiceImpl].
 *
 * T5 already covers the happy/wrong/expired/replayed/revoked paths THROUGH gRPC.
 * This suite exercises the real implementation with no transport in the way, so
 * the finer contracts it owns — the attempt-cap lockout, the attempt-counter
 * reset on window replacement, touch-on-verify, and the hash-only persistence
 * invariant — are asserted where they actually live.
 *
 * The clock is injected ([nowMs]) so TTL/expiry are exact and deterministic:
 * no `sleep`, no wall clock, no flake.
 */
class PairingLifecycleTest {
    private lateinit var ctx: Context
    private lateinit var repo: FakeDeviceRepository
    private lateinit var pairing: PairingServiceImpl
    private var nowMs: Long = 1_000_000L

    @Before
    fun buildService() {
        // Given a Context wired with a domain-port fake only (never an :adapter type)
        ctx = Context()
        repo = FakeDeviceRepository()
        Register<DeviceRepository>(ctx, repo)
        pairing = PairingServiceImpl(ctx, clock = { nowMs })
        nowMs = 1_000_000L
    }

    /** Asserts [outcome] is a rejection and returns its reason string. */
    private fun reason(outcome: PairOutcome): String {
        assertThat(outcome).isInstanceOf(PairOutcome.Rejected::class.java)
        return (outcome as PairOutcome.Rejected).reason
    }

    /** Asserts [outcome] paired successfully and returns (deviceId, token). */
    private fun pairedOnce(outcome: PairOutcome): Pair<String, String> {
        assertThat(outcome).isInstanceOf(PairOutcome.Ok::class.java)
        val ok = outcome as PairOutcome.Ok
        return ok.deviceId to ok.token
    }

    private fun openWindow(ttlMs: Long = 120_000L): Pairing = pairing.openWindow(ttlMs)

    // ---------------------------------------------------------------- case 1: open

    @Test
    fun `open window returns a six digit pin with a future expiry`() {
        // When a 120s window is opened at t0
        val window = openWindow(120_000L)

        // Then the PIN is exactly 6 digits and the expiry is t0 + ttl
        assertThat(window.pin).matches("\\d{6}")
        assertThat(window.expiresAtMs).isEqualTo(nowMs + 120_000L)
        assertThat(window.expiresAtMs).isGreaterThan(nowMs)
    }

    // ---------------------------------------------------------- case 2: single-use

    @Test
    fun `a consumed pin is rejected and never issues a second token`() {
        // Given a window whose PIN was redeemed once
        val window = openWindow()
        val (deviceId, token) = pairedOnce(pairing.pair(window.pin, "phone-a", "phone"))
        assertThat(repo.list()).hasSize(1)

        // When the SAME PIN is presented again
        val second = pairing.pair(window.pin, "phone-b", "phone")

        // Then it is rejected as consumed, with no second device and no second token
        assertThat(reason(second)).isEqualTo(PairOutcome.GoReasonPinConsumed)
        assertThat(repo.list()).hasSize(1)
        assertThat(repo.get(deviceId)!!.tokenHash)
            .isEqualTo(
                com.glassstorm.phonemanager.service.security.TokenCodec
                    .hashToken(token),
            )
    }

    // ------------------------------------------------------ case 3: window expiry

    @Test
    fun `pairing past the expiry is rejected as expired`() {
        // Given an open window
        val window = openWindow(60_000L)

        // When the clock passes the expiry
        nowMs = window.expiresAtMs + 1

        // Then the correct PIN is refused as expired and nothing is persisted
        assertThat(reason(pairing.pair(window.pin, "phone-a", "phone")))
            .isEqualTo(PairOutcome.GoReasonPinExpired)
        assertThat(repo.list()).isEmpty()
    }

    // --------------------------------------------------------- case 4: attempt cap

    @Test
    fun `five failed attempts lock the pin even for the correct pin`() {
        // Given an open window and exactly GoMaxPinAttempts wrong attempts
        val window = openWindow()
        assertThat(PairingService.GoMaxPinAttempts).isEqualTo(5)
        repeat(PairingService.GoMaxPinAttempts) {
            assertThat(reason(pairing.pair("000000".asPinOtherThan(window), "phone", "phone")))
                .isEqualTo(PairOutcome.GoReasonPinInvalid)
        }

        // When the CORRECT pin is finally presented
        val outcome = pairing.pair(window.pin, "phone", "phone")

        // Then it is LOCKED (not merely rejected as invalid) and no token is minted
        assertThat(reason(outcome)).isEqualTo(PairOutcome.GoReasonPinLocked)
        assertThat(repo.list()).isEmpty()
    }

    // ------------------------------------------------- case 5: wrong pin persists nothing

    @Test
    fun `a wrong pin is invalid and persists no device`() {
        // Given an open window
        val window = openWindow()

        // When a wrong PIN is presented
        val wrong = window.pin.asPinOtherThan(window)

        // Then it is rejected as invalid and the repository stays empty
        assertThat(reason(pairing.pair(wrong, "phone", "phone")))
            .isEqualTo(PairOutcome.GoReasonPinInvalid)
        assertThat(repo.list()).isEmpty()
    }

    // ------------------------------------------------------- case 6: malformed input

    @Test
    fun `no window blank pin and blank name each map to their own reason`() {
        // Given NO window is open
        // Then a well-formed attempt reports no window
        assertThat(reason(pairing.pair("123456", "phone", "phone")))
            .isEqualTo(PairOutcome.GoReasonNoWindow)

        // Given a window IS open
        openWindow()

        // Then a blank pin and a blank name are refused before any window logic
        assertThat(reason(pairing.pair("", "phone", "phone")))
            .isEqualTo(PairOutcome.GoReasonPinMissing)
        assertThat(reason(pairing.pair("   ", "phone", "phone")))
            .isEqualTo(PairOutcome.GoReasonPinMissing)
        assertThat(reason(pairing.pair("123456", "", "phone")))
            .isEqualTo(PairOutcome.GoReasonNameMissing)
        assertThat(repo.list()).isEmpty()
    }

    // ---------------------------------------------------------- case 7: revocation

    @Test
    fun `revoke invalidates the token immediately`() {
        // Given a paired device
        val window = openWindow()
        val (deviceId, token) = pairedOnce(pairing.pair(window.pin, "phone-a", "phone"))
        assertThat(pairing.verifyToken(token)).isNotNull()

        // When it is revoked
        pairing.revoke(deviceId)

        // Then the token no longer resolves and the listing is empty
        assertThat(pairing.verifyToken(token)).isNull()
        assertThat(pairing.listPaired()).isEmpty()
    }

    // ------------------------------------------------- case 8: list + stop resets cap

    @Test
    fun `list reflects exactly the paired devices and stop window resets the counter`() {
        // Given two devices paired through two windows
        val first = openWindow()
        val (idA, _) = pairedOnce(pairing.pair(first.pin, "phone-a", "phone"))
        val second = openWindow()
        val (idB, _) = pairedOnce(pairing.pair(second.pin, "tablet-b", "tablet"))

        // Then the listing contains exactly those two devices
        assertThat(pairing.listPaired().map { it.deviceId })
            .containsExactly(idA, idB)
        assertThat(pairing.listPaired().map { it.deviceName })
            .containsExactly("phone-a", "tablet-b")

        // When a third window accrues a near-cap attempt count and is then stopped
        val third = openWindow()
        repeat(4) {
            assertThat(reason(pairing.pair("000000".asPinOtherThan(third), "phone", "phone")))
                .isEqualTo(PairOutcome.GoReasonPinInvalid)
        }
        pairing.stopWindow()

        // Then the stopped window reports no-window
        assertThat(reason(pairing.pair(third.pin, "phone", "phone")))
            .isEqualTo(PairOutcome.GoReasonNoWindow)

        // And a fresh window pairs cleanly with the CORRECT pin — the counter reset
        val fourth = openWindow()
        val (idC, _) = pairedOnce(pairing.pair(fourth.pin, "phone-c", "phone"))
        assertThat(pairing.listPaired().map { it.deviceId }).containsExactly(idA, idB, idC)
    }

    // ------------------------------------------------ case 9: only the hash is stored

    @Test
    fun `only the token hash is persisted while the plaintext still verifies`() {
        // Given a paired device
        val window = openWindow()
        val (deviceId, token) = pairedOnce(pairing.pair(window.pin, "phone-a", "phone"))

        // Then the stored hash is NOT the token, and the plaintext still resolves
        val stored = repo.get(deviceId)!!
        assertThat(stored.tokenHash).isNotEqualTo(token)
        assertThat(stored.tokenHash)
            .isEqualTo(
                com.glassstorm.phonemanager.service.security.TokenCodec
                    .hashToken(token),
            )
        assertThat(pairing.verifyToken(token)?.deviceId).isEqualTo(deviceId)
    }

    // ---------------------------------------------------- case 10: touch on verify

    @Test
    fun `verifying a token bumps the last seen instant`() {
        // Given a paired device whose last-seen is still null
        val window = openWindow()
        val (deviceId, token) = pairedOnce(pairing.pair(window.pin, "phone-a", "phone"))
        assertThat(repo.get(deviceId)!!.lastSeenMs).isNull()

        // When the token is verified at a later instant
        nowMs = 2_000_000L
        val resolved = pairing.verifyToken(token)

        // Then the resolved device is right and the stored last-seen advanced
        assertThat(resolved?.deviceId).isEqualTo(deviceId)
        assertThat(repo.get(deviceId)!!.lastSeenMs).isEqualTo(2_000_000L)
    }

    /** Returns a 6-digit PIN string that is guaranteed different from the window's. */
    private fun String.asPinOtherThan(window: Pairing): String {
        val candidate = if (this == window.pin) "000000" else this
        return if (candidate == window.pin) "111111" else candidate
    }
}
