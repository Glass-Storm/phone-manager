package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.core.model.PairOutcome
import com.glassstorm.phonemanager.core.model.Pairing
import com.glassstorm.phonemanager.core.service.security.TokenCodec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single-use pairing window: state, attempt cap, and the pair decision.
 *
 * This service owns ONE responsibility — deciding whether a presented PIN may be
 * redeemed against the current window. It mints no token and persists no device;
 * the orchestrator [PairingServiceImpl] performs those side effects only after
 * [decide] returns [PairWindowDecision.Accepted].
 *
 * ## Window state is in-memory (documented)
 *
 * At most ONE window exists at a time. It is a short-lived bootstrap secret
 * (seconds to minutes), so it is deliberately not persisted: a hub restart closes
 * the window, which is the safe default.
 *
 * ## Attempt cap
 *
 * [DeviceRepository] has no attempt API, so the failed-attempt counter lives here
 * alongside the window it guards. Once [PairingService.MAX_PIN_ATTEMPTS] failures
 * accumulate, the current PIN is locked; only a fresh window clears it. The
 * counter is PER-WINDOW and IN-MEMORY: a hub restart clears it, and a restart also
 * closes the window, so there is nothing left to brute-force.
 *
 * ## Concurrency
 *
 * [openWindow], [stopWindow] and [decide] are `@Synchronized` on this instance, so
 * the check-and-consume and the failed-attempt increment are each atomic. Without
 * it two callers holding the correct PIN could both pass the "not consumed" check
 * and redeem one single-use PIN twice, and concurrent bad PINs could lose
 * increments and let a brute-force slip past [PairingService.MAX_PIN_ATTEMPTS].
 */
@Singleton
class PairingWindowService
    private constructor(
        private val clock: () -> Long,
    ) {
        @Inject
        constructor() : this(System::currentTimeMillis)

        private var window: Pairing? = null
        private var windowConsumed: Boolean = false
        private var failedAttempts: Int = 0

        /** Open a fresh single-use window, replacing any existing one. */
        @Synchronized
        fun openWindow(ttlMs: Long): Pairing {
            val fresh = Pairing(pin = TokenCodec.newPin(), expiresAtMs = clock() + ttlMs)
            window = fresh
            windowConsumed = false
            failedAttempts = 0
            return fresh
        }

        /** Close the current window and clear its attempt counter. */
        @Synchronized
        fun stopWindow() {
            window = null
            windowConsumed = false
            failedAttempts = 0
        }

        /**
         * Decide whether [pin] may be redeemed for [deviceName] against the current
         * window. Validation order is fixed and observable through the reason.
         */
        @Synchronized
        fun decide(
            pin: String,
            deviceName: String,
        ): PairWindowDecision {
            if (pin.isBlank()) return PairWindowDecision.Rejected(PairOutcome.REASON_PIN_MISSING)
            if (deviceName.isBlank()) return PairWindowDecision.Rejected(PairOutcome.REASON_NAME_MISSING)

            val current = window ?: return PairWindowDecision.Rejected(PairOutcome.REASON_NO_WINDOW)
            if (clock() > current.expiresAtMs) return PairWindowDecision.Rejected(PairOutcome.REASON_PIN_EXPIRED)
            if (windowConsumed) return PairWindowDecision.Rejected(PairOutcome.REASON_PIN_CONSUMED)
            if (failedAttempts >= PairingService.MAX_PIN_ATTEMPTS) {
                return PairWindowDecision.Rejected(PairOutcome.REASON_PIN_LOCKED)
            }

            // Constant-time PIN check — never `String.equals` on a secret.
            if (!TokenCodec.constantTimeEquals(current.pin, pin)) {
                failedAttempts += 1
                return PairWindowDecision.Rejected(PairOutcome.REASON_PIN_INVALID)
            }

            // Success: burn the single-use PIN before the caller mints anything, so a
            // crash mid-issue cannot replay it.
            windowConsumed = true
            failedAttempts = 0
            return PairWindowDecision.Accepted
        }

        companion object {
            /** Test seam: builds the window service against a deterministic clock. */
            fun withClock(clock: () -> Long): PairingWindowService = PairingWindowService(clock)
        }
    }

/** The decision [PairingWindowService.decide] returns: redeemable, or a reason why not. */
sealed interface PairWindowDecision {
    /** The PIN is valid, unexpired, unconsumed, and under the attempt cap. */
    data object Accepted : PairWindowDecision

    /** The attempt is refused for [reason] (a `PairOutcome.reason*` value). */
    data class Rejected(
        val reason: String,
    ) : PairWindowDecision
}
