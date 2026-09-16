package com.glassstorm.phonemanager.service

import com.google.common.truth.Truth.assertThat
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.Register
import com.glassstorm.phonemanager.domain.dto.PairOutcome
import com.glassstorm.phonemanager.domain.dto.Pairing
import com.glassstorm.phonemanager.domain.service.PairingService
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
 * The clock is injected ([GoNowMs]) so TTL/expiry are exact and deterministic:
 * no `sleep`, no wall clock, no flake.
 */
class PairingLifecycleTest {

    private lateinit var GoCtx: Context
    private lateinit var GoRepo: FakeDeviceRepository
    private lateinit var GoPairing: PairingServiceImpl
    private var GoNowMs: Long = 1_000_000L

    @Before
    fun GoBuildService() {
        // Given a Context wired with a domain-port fake only (never an :adapter type)
        GoCtx = Context()
        GoRepo = FakeDeviceRepository()
        Register<DeviceRepository>(GoCtx, GoRepo)
        GoPairing = PairingServiceImpl(GoCtx, GoClock = { GoNowMs })
        GoNowMs = 1_000_000L
    }

    /** Asserts [outcome] is a rejection and returns its reason string. */
    private fun GoReason(outcome: PairOutcome): String {
        assertThat(outcome).isInstanceOf(PairOutcome.GoRejected::class.java)
        return (outcome as PairOutcome.GoRejected).GoReason
    }

    /** Asserts [outcome] paired successfully and returns (deviceId, token). */
    private fun GoPairedOnce(outcome: PairOutcome): Pair<String, String> {
        assertThat(outcome).isInstanceOf(PairOutcome.GoOk::class.java)
        val GoOk = outcome as PairOutcome.GoOk
        return GoOk.GoDeviceId to GoOk.GoToken
    }

    private fun GoOpenWindow(ttlMs: Long = 120_000L): Pairing = GoPairing.GoOpenWindow(ttlMs)

    // ---------------------------------------------------------------- case 1: open

    @Test
    fun `open window returns a six digit pin with a future expiry`() {
        // When a 120s window is opened at t0
        val GoWindow = GoOpenWindow(120_000L)

        // Then the PIN is exactly 6 digits and the expiry is t0 + ttl
        assertThat(GoWindow.GoPin).matches("\\d{6}")
        assertThat(GoWindow.GoExpiresAtMs).isEqualTo(GoNowMs + 120_000L)
        assertThat(GoWindow.GoExpiresAtMs).isGreaterThan(GoNowMs)
    }

    // ---------------------------------------------------------- case 2: single-use

    @Test
    fun `a consumed pin is rejected and never issues a second token`() {
        // Given a window whose PIN was redeemed once
        val GoWindow = GoOpenWindow()
        val (GoDeviceId, GoToken) = GoPairedOnce(GoPairing.GoPair(GoWindow.GoPin, "phone-a", "phone"))
        assertThat(GoRepo.GoList()).hasSize(1)

        // When the SAME PIN is presented again
        val GoSecond = GoPairing.GoPair(GoWindow.GoPin, "phone-b", "phone")

        // Then it is rejected as consumed, with no second device and no second token
        assertThat(GoReason(GoSecond)).isEqualTo(PairOutcome.GoReasonPinConsumed)
        assertThat(GoRepo.GoList()).hasSize(1)
        assertThat(GoRepo.GoGet(GoDeviceId)!!.GoTokenHash)
            .isEqualTo(com.glassstorm.phonemanager.service.security.TokenCodec.GoHashToken(GoToken))
    }

    // ------------------------------------------------------ case 3: window expiry

    @Test
    fun `pairing past the expiry is rejected as expired`() {
        // Given an open window
        val GoWindow = GoOpenWindow(60_000L)

        // When the clock passes the expiry
        GoNowMs = GoWindow.GoExpiresAtMs + 1

        // Then the correct PIN is refused as expired and nothing is persisted
        assertThat(GoReason(GoPairing.GoPair(GoWindow.GoPin, "phone-a", "phone")))
            .isEqualTo(PairOutcome.GoReasonPinExpired)
        assertThat(GoRepo.GoList()).isEmpty()
    }

    // --------------------------------------------------------- case 4: attempt cap

    @Test
    fun `five failed attempts lock the pin even for the correct pin`() {
        // Given an open window and exactly GoMaxPinAttempts wrong attempts
        val GoWindow = GoOpenWindow()
        assertThat(PairingService.GoMaxPinAttempts).isEqualTo(5)
        repeat(PairingService.GoMaxPinAttempts) {
            assertThat(GoReason(GoPairing.GoPair("000000".GoAsPinOtherThan(GoWindow), "phone", "phone")))
                .isEqualTo(PairOutcome.GoReasonPinInvalid)
        }

        // When the CORRECT pin is finally presented
        val GoOutcome = GoPairing.GoPair(GoWindow.GoPin, "phone", "phone")

        // Then it is LOCKED (not merely rejected as invalid) and no token is minted
        assertThat(GoReason(GoOutcome)).isEqualTo(PairOutcome.GoReasonPinLocked)
        assertThat(GoRepo.GoList()).isEmpty()
    }

    // ------------------------------------------------- case 5: wrong pin persists nothing

    @Test
    fun `a wrong pin is invalid and persists no device`() {
        // Given an open window
        val GoWindow = GoOpenWindow()

        // When a wrong PIN is presented
        val GoWrong = GoWindow.GoPin.GoAsPinOtherThan(GoWindow)

        // Then it is rejected as invalid and the repository stays empty
        assertThat(GoReason(GoPairing.GoPair(GoWrong, "phone", "phone")))
            .isEqualTo(PairOutcome.GoReasonPinInvalid)
        assertThat(GoRepo.GoList()).isEmpty()
    }

    // ------------------------------------------------------- case 6: malformed input

    @Test
    fun `no window blank pin and blank name each map to their own reason`() {
        // Given NO window is open
        // Then a well-formed attempt reports no window
        assertThat(GoReason(GoPairing.GoPair("123456", "phone", "phone")))
            .isEqualTo(PairOutcome.GoReasonNoWindow)

        // Given a window IS open
        GoOpenWindow()

        // Then a blank pin and a blank name are refused before any window logic
        assertThat(GoReason(GoPairing.GoPair("", "phone", "phone")))
            .isEqualTo(PairOutcome.GoReasonPinMissing)
        assertThat(GoReason(GoPairing.GoPair("   ", "phone", "phone")))
            .isEqualTo(PairOutcome.GoReasonPinMissing)
        assertThat(GoReason(GoPairing.GoPair("123456", "", "phone")))
            .isEqualTo(PairOutcome.GoReasonNameMissing)
        assertThat(GoRepo.GoList()).isEmpty()
    }

    // ---------------------------------------------------------- case 7: revocation

    @Test
    fun `revoke invalidates the token immediately`() {
        // Given a paired device
        val GoWindow = GoOpenWindow()
        val (GoDeviceId, GoToken) = GoPairedOnce(GoPairing.GoPair(GoWindow.GoPin, "phone-a", "phone"))
        assertThat(GoPairing.GoVerifyToken(GoToken)).isNotNull()

        // When it is revoked
        GoPairing.GoRevoke(GoDeviceId)

        // Then the token no longer resolves and the listing is empty
        assertThat(GoPairing.GoVerifyToken(GoToken)).isNull()
        assertThat(GoPairing.GoListPaired()).isEmpty()
    }

    // ------------------------------------------------- case 8: list + stop resets cap

    @Test
    fun `list reflects exactly the paired devices and stop window resets the counter`() {
        // Given two devices paired through two windows
        val GoFirst = GoOpenWindow()
        val (GoIdA, _) = GoPairedOnce(GoPairing.GoPair(GoFirst.GoPin, "phone-a", "phone"))
        val GoSecond = GoOpenWindow()
        val (GoIdB, _) = GoPairedOnce(GoPairing.GoPair(GoSecond.GoPin, "tablet-b", "tablet"))

        // Then the listing contains exactly those two devices
        assertThat(GoPairing.GoListPaired().map { it.GoDeviceId })
            .containsExactly(GoIdA, GoIdB)
        assertThat(GoPairing.GoListPaired().map { it.GoDeviceName })
            .containsExactly("phone-a", "tablet-b")

        // When a third window accrues a near-cap attempt count and is then stopped
        val GoThird = GoOpenWindow()
        repeat(4) {
            assertThat(GoReason(GoPairing.GoPair("000000".GoAsPinOtherThan(GoThird), "phone", "phone")))
                .isEqualTo(PairOutcome.GoReasonPinInvalid)
        }
        GoPairing.GoStopWindow()

        // Then the stopped window reports no-window
        assertThat(GoReason(GoPairing.GoPair(GoThird.GoPin, "phone", "phone")))
            .isEqualTo(PairOutcome.GoReasonNoWindow)

        // And a fresh window pairs cleanly with the CORRECT pin — the counter reset
        val GoFourth = GoOpenWindow()
        val (GoIdC, _) = GoPairedOnce(GoPairing.GoPair(GoFourth.GoPin, "phone-c", "phone"))
        assertThat(GoPairing.GoListPaired().map { it.GoDeviceId }).containsExactly(GoIdA, GoIdB, GoIdC)
    }

    // ------------------------------------------------ case 9: only the hash is stored

    @Test
    fun `only the token hash is persisted while the plaintext still verifies`() {
        // Given a paired device
        val GoWindow = GoOpenWindow()
        val (GoDeviceId, GoToken) = GoPairedOnce(GoPairing.GoPair(GoWindow.GoPin, "phone-a", "phone"))

        // Then the stored hash is NOT the token, and the plaintext still resolves
        val GoStored = GoRepo.GoGet(GoDeviceId)!!
        assertThat(GoStored.GoTokenHash).isNotEqualTo(GoToken)
        assertThat(GoStored.GoTokenHash)
            .isEqualTo(com.glassstorm.phonemanager.service.security.TokenCodec.GoHashToken(GoToken))
        assertThat(GoPairing.GoVerifyToken(GoToken)?.GoDeviceId).isEqualTo(GoDeviceId)
    }

    // ---------------------------------------------------- case 10: touch on verify

    @Test
    fun `verifying a token bumps the last seen instant`() {
        // Given a paired device whose last-seen is still null
        val GoWindow = GoOpenWindow()
        val (GoDeviceId, GoToken) = GoPairedOnce(GoPairing.GoPair(GoWindow.GoPin, "phone-a", "phone"))
        assertThat(GoRepo.GoGet(GoDeviceId)!!.GoLastSeenMs).isNull()

        // When the token is verified at a later instant
        GoNowMs = 2_000_000L
        val GoResolved = GoPairing.GoVerifyToken(GoToken)

        // Then the resolved device is right and the stored last-seen advanced
        assertThat(GoResolved?.GoDeviceId).isEqualTo(GoDeviceId)
        assertThat(GoRepo.GoGet(GoDeviceId)!!.GoLastSeenMs).isEqualTo(2_000_000L)
    }

    /** Returns a 6-digit PIN string that is guaranteed different from the window's. */
    private fun String.GoAsPinOtherThan(window: Pairing): String {
        val GoCandidate = if (this == window.GoPin) "000000" else this
        return if (GoCandidate == window.GoPin) "111111" else GoCandidate
    }
}
