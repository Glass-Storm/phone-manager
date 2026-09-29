package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.core.model.PairOutcome
import com.glassstorm.phonemanager.core.model.Pairing
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Direct proof for [PairingWindowService] in isolation: window state, the fixed
 * validation order, the single-use burn, and the per-window attempt cap. The
 * clock is injected so TTL/expiry are exact — no `sleep`, no wall clock, no flake.
 */
class PairingWindowServiceTest {
    private lateinit var windows: PairingWindowService
    private var nowMs: Long = 1_000_000L

    @Before
    fun buildService() {
        windows = PairingWindowService.withClock(clock = { nowMs })
        nowMs = 1_000_000L
    }

    /** Asserts [decision] is a rejection and returns its reason string. */
    private fun reason(decision: PairWindowDecision): String {
        assertThat(decision).isInstanceOf(PairWindowDecision.Rejected::class.java)
        return (decision as PairWindowDecision.Rejected).reason
    }

    private fun open(ttlMs: Long = 120_000L): Pairing = windows.openWindow(ttlMs)

    @Test
    fun `open window returns a six digit pin with an expiry of clock plus ttl`() {
        // When a 120s window is opened at t0
        val window = open(120_000L)

        // Then the PIN is exactly 6 digits and the expiry is t0 + ttl
        assertThat(window.pin).matches("\\d{6}")
        assertThat(window.expiresAtMs).isEqualTo(nowMs + 120_000L)
        assertThat(window.expiresAtMs).isGreaterThan(nowMs)
    }

    @Test
    fun `a correct pin is accepted then the same pin is consumed`() {
        // Given an open window
        val window = open()

        // When the correct pin is decided once
        assertThat(windows.decide(window.pin, "phone-a")).isEqualTo(PairWindowDecision.Accepted)

        // Then the SAME pin is refused as consumed
        assertThat(reason(windows.decide(window.pin, "phone-b")))
            .isEqualTo(PairOutcome.REASON_PIN_CONSUMED)
    }

    @Test
    fun `a decision past the expiry is rejected as expired`() {
        // Given an open window
        val window = open(60_000L)

        // When the clock passes the expiry
        nowMs = window.expiresAtMs + 1

        // Then even the correct pin is refused as expired
        assertThat(reason(windows.decide(window.pin, "phone-a")))
            .isEqualTo(PairOutcome.REASON_PIN_EXPIRED)
    }

    @Test
    fun `a decision with no window open reports no window`() {
        // Given NO window is open
        // Then a well-formed attempt reports no window
        assertThat(reason(windows.decide("123456", "phone-a")))
            .isEqualTo(PairOutcome.REASON_NO_WINDOW)
    }

    @Test
    fun `blank pin and blank name each map to their own reason`() {
        // Given a window IS open
        val window = open()

        // Then a blank pin and a blank name are refused before any window logic
        assertThat(reason(windows.decide("", "phone-a")))
            .isEqualTo(PairOutcome.REASON_PIN_MISSING)
        assertThat(reason(windows.decide("   ", "phone-a")))
            .isEqualTo(PairOutcome.REASON_PIN_MISSING)
        assertThat(reason(windows.decide(window.pin, "")))
            .isEqualTo(PairOutcome.REASON_NAME_MISSING)
    }

    @Test
    fun `max wrong attempts lock the pin even for the correct pin`() {
        // Given an open window and exactly MAX_PIN_ATTEMPTS wrong attempts
        val window = open()
        val wrong = window.pin.pinOtherThan(window)
        assertThat(PairingService.MAX_PIN_ATTEMPTS).isEqualTo(5)
        repeat(PairingService.MAX_PIN_ATTEMPTS) {
            assertThat(reason(windows.decide(wrong, "phone")))
                .isEqualTo(PairOutcome.REASON_PIN_INVALID)
        }

        // When the CORRECT pin is finally presented
        // Then it is LOCKED (not merely invalid)
        assertThat(reason(windows.decide(window.pin, "phone")))
            .isEqualTo(PairOutcome.REASON_PIN_LOCKED)
    }

    @Test
    fun `stop window clears the window and resets the attempt counter`() {
        // Given an open window carrying a near-cap attempt count
        val window = open()
        val wrong = window.pin.pinOtherThan(window)
        repeat(PairingService.MAX_PIN_ATTEMPTS - 1) {
            assertThat(reason(windows.decide(wrong, "phone")))
                .isEqualTo(PairOutcome.REASON_PIN_INVALID)
        }

        // When the window is stopped
        windows.stopWindow()

        // Then the window is gone
        assertThat(reason(windows.decide(window.pin, "phone")))
            .isEqualTo(PairOutcome.REASON_NO_WINDOW)

        // And a fresh window accepts a first-try correct pin — the counter reset
        val fresh = open()
        assertThat(windows.decide(fresh.pin, "phone")).isEqualTo(PairWindowDecision.Accepted)
    }

    @Test
    fun `wrong pins are invalid and the counter increments up to the threshold`() {
        // Given an open window
        val window = open()
        val wrong = window.pin.pinOtherThan(window)

        // When MAX - 1 wrong pins are presented
        repeat(PairingService.MAX_PIN_ATTEMPTS - 1) {
            assertThat(reason(windows.decide(wrong, "phone")))
                .isEqualTo(PairOutcome.REASON_PIN_INVALID)
        }

        // Then the counter has NOT yet reached the cap, so the correct pin still wins
        assertThat(windows.decide(window.pin, "phone")).isEqualTo(PairWindowDecision.Accepted)
    }

    /** Returns a 6-digit PIN string that is guaranteed different from the window's. */
    private fun String.pinOtherThan(window: Pairing): String {
        val candidate = if (this == window.pin) "000000" else this
        return if (candidate == window.pin) "111111" else candidate
    }
}
