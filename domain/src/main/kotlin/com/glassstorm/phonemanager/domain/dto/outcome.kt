package com.glassstorm.phonemanager.domain.dto

/**
 * The machine-checkable result of a pairing attempt. Pure data.
 *
 * A rejection is a normal outcome (a wrong/expired/replayed PIN is the expected
 * behaviour of an untrusted peer), so [PairOutcome] is a value, never an
 * exception. [GoOk] carries the issued secret material; [GoRejected] carries a
 * reason from the `GoReason*` vocabulary that callers switch on exhaustively.
 */
sealed interface PairOutcome {
    /** A device was paired: [GoDeviceId] plus the one-time-visible [GoToken]. */
    data class GoOk(val GoDeviceId: String, val GoToken: String) : PairOutcome

    /** The attempt was refused for [GoReason]. Nothing was persisted, no token exists. */
    data class GoRejected(val GoReason: String) : PairOutcome

    companion object {
        /** No pairing window is currently open. */
        const val GoReasonNoWindow: String = "no-window"

        /** The window is open but the supplied PIN is not the window PIN. */
        const val GoReasonPinInvalid: String = "pin-invalid"

        /** The window's TTL has elapsed. */
        const val GoReasonPinExpired: String = "pin-expired"

        /** The PIN was already consumed; recovery requires a fresh window. */
        const val GoReasonPinConsumed: String = "pin-consumed"

        /** Too many failed attempts against the current PIN. */
        const val GoReasonPinLocked: String = "pin-locked"

        /** The request carried no usable PIN at all. */
        const val GoReasonPinMissing: String = "pin-missing"

        /** The request carried no device name. */
        const val GoReasonNameMissing: String = "name-missing"
    }
}
