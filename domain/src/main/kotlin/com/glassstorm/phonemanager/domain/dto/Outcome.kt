package com.glassstorm.phonemanager.domain.dto

/**
 * The machine-checkable result of a pairing attempt. Pure data.
 *
 * A rejection is a normal outcome (a wrong/expired/replayed PIN is the expected
 * behaviour of an untrusted peer), so [PairOutcome] is a value, never an
 * exception. [Ok] carries the issued secret material; [Rejected] carries a
 * reason from the `reason*` vocabulary that callers switch on exhaustively.
 */
sealed interface PairOutcome {
    /** A device was paired: [deviceId] plus the one-time-visible [token]. */
    data class Ok(
        val deviceId: String,
        val token: String,
    ) : PairOutcome

    /** The attempt was refused for [reason]. Nothing was persisted, no token exists. */
    data class Rejected(
        val reason: String,
    ) : PairOutcome

    companion object {
        /** No pairing window is currently open. */
        const val REASON_NO_WINDOW: String = "no-window"

        /** The window is open but the supplied PIN is not the window PIN. */
        const val REASON_PIN_INVALID: String = "pin-invalid"

        /** The window's TTL has elapsed. */
        const val REASON_PIN_EXPIRED: String = "pin-expired"

        /** The PIN was already consumed; recovery requires a fresh window. */
        const val REASON_PIN_CONSUMED: String = "pin-consumed"

        /** Too many failed attempts against the current PIN. */
        const val REASON_PIN_LOCKED: String = "pin-locked"

        /** The request carried no usable PIN at all. */
        const val REASON_PIN_MISSING: String = "pin-missing"

        /** The request carried no device name. */
        const val REASON_NAME_MISSING: String = "name-missing"
    }
}
