package com.glassstorm.phonemanager.domain.network

import com.glassstorm.phonemanager.domain.dto.PeerAddress

/**
 * Lifecycle of hub peer discovery.
 *
 * Pure JVM: no Android types, so the whole flow is testable on a plain JVM.
 * `IDLE -> ADVERTISING -> RESOLVING -> (RESOLVED via mDNS | FALLBACK to gateway | TIMEOUT)`.
 * [FALLBACK] is a normal terminal state — the direct gateway IP is the primary
 * path in the manual-tether development flow — while [TIMEOUT] means no fallback
 * was configured either.
 */
enum class DiscoveryState {
    IDLE,
    ADVERTISING,
    RESOLVING,
    RESOLVED,
    FALLBACK,
    TIMEOUT,
}

/** Inputs the adapter feeds into [DiscoveryStateMachine]. */
enum class DiscoveryEvent {
    START_ADVERTISE,
    ADVERTISE_REGISTERED,
    ADVERTISE_FAILED,
    START_RESOLVE,
    MDNS_RESOLVED,
    MDNS_FAILED,
    RESOLVE_TIMEOUT,
    STOP_REQUESTED,
}

/** Typed reasons a discovery action could not proceed. Never a bare string. */
sealed interface DiscoveryFailure {
    /** `registerService` failed (listener `onRegistrationFailed`). */
    data class AdvertiseFailed(val GoReason: String) : DiscoveryFailure

    /** mDNS resolution failed (`onResolveFailed`). */
    data class ResolveFailed(val GoReason: String) : DiscoveryFailure

    /** The resolution window elapsed with no peer. */
    data object ResolveTimedOut : DiscoveryFailure

    /** The event is not valid for the current state. */
    data class IllegalTransition(
        val GoFrom: DiscoveryState,
        val GoEvent: DiscoveryEvent,
    ) : DiscoveryFailure
}

/** Outcome of feeding one event into the machine. */
sealed interface DiscoveryTransition {
    /** The machine changed to (or idempotently remained in) [GoState]. */
    data class Moved(val GoState: DiscoveryState) : DiscoveryTransition

    /** The event was refused; the machine stayed in [GoState] and [GoFailure] says why. */
    data class Rejected(
        val GoState: DiscoveryState,
        val GoFailure: DiscoveryFailure,
    ) : DiscoveryTransition
}

/** Outcome of picking between an mDNS hit and the configured direct-IP gateway. */
sealed interface DiscoverySelection {
    data class Selected(val GoPeer: PeerAddress) : DiscoverySelection

    data object NoPeer : DiscoverySelection
}

/**
 * Deterministic discovery lifecycle state machine.
 *
 * Not thread-safe by design: the adapter owns it. Every `when` over
 * [DiscoveryState]/[DiscoveryEvent] is exhaustive, so a newly added state or
 * event becomes a compile error rather than a silent fall-through.
 *
 * [GoGatewayFallback] is the direct-IP address used when mDNS misses; when it is
 * `null` a miss degrades to [DiscoveryState.TIMEOUT] instead of a fallback.
 */
class DiscoveryStateMachine(
    private val GoGatewayFallback: PeerAddress?,
) {

    var GoState: DiscoveryState = DiscoveryState.IDLE
        private set

    /** The cause of the most recent refusal/failure, for diagnostics. */
    var GoLastFailure: DiscoveryFailure? = null
        private set

    fun GoAccept(event: DiscoveryEvent, failure: DiscoveryFailure? = null): DiscoveryTransition =
        when (GoState) {
            DiscoveryState.IDLE -> GoFromIdle(event, failure)
            DiscoveryState.ADVERTISING -> GoFromAdvertising(event, failure)
            DiscoveryState.RESOLVING -> GoFromResolving(event, failure)
            DiscoveryState.RESOLVED -> GoFromSettled(event, failure)
            DiscoveryState.FALLBACK -> GoFromSettled(event, failure)
            DiscoveryState.TIMEOUT -> GoFromSettled(event, failure)
        }

    /**
     * Prefer a real mDNS peer over the gateway fallback.
     *
     * The fallback is deliberately second so a later mDNS hit still wins.
     */
    fun GoSelectPeer(mdns: PeerAddress?, gateway: PeerAddress?): DiscoverySelection {
        val GoWinner = mdns ?: gateway ?: return DiscoverySelection.NoPeer
        return DiscoverySelection.Selected(GoWinner)
    }

    private fun GoFromIdle(event: DiscoveryEvent, failure: DiscoveryFailure?): DiscoveryTransition =
        when (event) {
            DiscoveryEvent.START_ADVERTISE -> GoMoveTo(DiscoveryState.ADVERTISING)
            DiscoveryEvent.STOP_REQUESTED -> GoMoveTo(DiscoveryState.IDLE)
            DiscoveryEvent.ADVERTISE_FAILED -> GoReject(event, failure, DiscoveryState.IDLE)
            DiscoveryEvent.ADVERTISE_REGISTERED,
            DiscoveryEvent.START_RESOLVE,
            DiscoveryEvent.MDNS_RESOLVED,
            DiscoveryEvent.MDNS_FAILED,
            DiscoveryEvent.RESOLVE_TIMEOUT,
            -> GoReject(event, failure)
        }

    private fun GoFromAdvertising(event: DiscoveryEvent, failure: DiscoveryFailure?): DiscoveryTransition =
        when (event) {
            DiscoveryEvent.ADVERTISE_REGISTERED -> GoMoveTo(DiscoveryState.ADVERTISING)
            DiscoveryEvent.START_RESOLVE -> GoMoveTo(DiscoveryState.RESOLVING)
            DiscoveryEvent.ADVERTISE_FAILED -> {
                GoLastFailure = failure ?: DiscoveryFailure.AdvertiseFailed("advertise failed")
                GoState = DiscoveryState.IDLE
                DiscoveryTransition.Rejected(DiscoveryState.IDLE, GoLastFailure!!)
            }
            DiscoveryEvent.START_ADVERTISE -> GoMoveTo(DiscoveryState.ADVERTISING)
            DiscoveryEvent.STOP_REQUESTED -> GoMoveTo(DiscoveryState.IDLE)
            DiscoveryEvent.MDNS_RESOLVED,
            DiscoveryEvent.MDNS_FAILED,
            DiscoveryEvent.RESOLVE_TIMEOUT,
            -> GoReject(event, failure)
        }

    private fun GoFromResolving(event: DiscoveryEvent, failure: DiscoveryFailure?): DiscoveryTransition =
        when (event) {
            DiscoveryEvent.MDNS_RESOLVED -> GoMoveTo(DiscoveryState.RESOLVED)
            DiscoveryEvent.MDNS_FAILED -> GoMissToFallback(failure)
            DiscoveryEvent.RESOLVE_TIMEOUT -> GoMissToFallback(failure ?: DiscoveryFailure.ResolveTimedOut)
            DiscoveryEvent.START_ADVERTISE -> GoMoveTo(DiscoveryState.ADVERTISING)
            DiscoveryEvent.STOP_REQUESTED -> GoMoveTo(DiscoveryState.IDLE)
            DiscoveryEvent.ADVERTISE_REGISTERED,
            DiscoveryEvent.ADVERTISE_FAILED,
            DiscoveryEvent.START_RESOLVE,
            -> GoReject(event, failure)
        }

    private fun GoFromSettled(event: DiscoveryEvent, failure: DiscoveryFailure?): DiscoveryTransition =
        when (event) {
            DiscoveryEvent.START_RESOLVE -> GoMoveTo(DiscoveryState.RESOLVING)
            DiscoveryEvent.START_ADVERTISE -> GoMoveTo(DiscoveryState.ADVERTISING)
            DiscoveryEvent.STOP_REQUESTED -> GoMoveTo(DiscoveryState.IDLE)
            DiscoveryEvent.ADVERTISE_REGISTERED,
            DiscoveryEvent.ADVERTISE_FAILED,
            DiscoveryEvent.MDNS_RESOLVED,
            DiscoveryEvent.MDNS_FAILED,
            DiscoveryEvent.RESOLVE_TIMEOUT,
            -> GoReject(event, failure)
        }

    private fun GoMissToFallback(failure: DiscoveryFailure?): DiscoveryTransition {
        GoLastFailure = failure
        val GoNext = if (GoGatewayFallback != null) DiscoveryState.FALLBACK else DiscoveryState.TIMEOUT
        GoState = GoNext
        return DiscoveryTransition.Moved(GoNext)
    }

    private fun GoMoveTo(next: DiscoveryState): DiscoveryTransition {
        GoState = next
        return DiscoveryTransition.Moved(next)
    }

    private fun GoReject(
        event: DiscoveryEvent,
        failure: DiscoveryFailure?,
        state: DiscoveryState = GoState,
    ): DiscoveryTransition {
        val GoFailure = failure ?: DiscoveryFailure.IllegalTransition(state, event)
        GoLastFailure = GoFailure
        return DiscoveryTransition.Rejected(state, GoFailure)
    }
}

/**
 * Whether an app-held Wi-Fi multicast lock is required on this platform.
 *
 * `MulticastLock` is mandatory while the Wi-Fi stack filters multicast frames:
 * always below API 33, and on API 33 until the Tiramisu SDK extension reaches 7.
 * From API 34 the platform handles mDNS multicast without the app holding a lock.
 *
 * The Tiramisu extension version is only queried on API 33 — [goTiramisuExtensionVersion]
 * is never invoked below 33 or from 34 (it must not be, since the extension is a 33 construct).
 */
fun GoNeedsMulticastLock(goSdkInt: Int, goTiramisuExtensionVersion: () -> Int): Boolean =
    when {
        goSdkInt < 33 -> true
        goSdkInt == 33 -> goTiramisuExtensionVersion() < 7
        else -> false
    }

/**
 * Whether resolution must use `registerServiceInfoCallback` instead of the
 * deprecated `resolveService` overload.
 *
 * The plan freezes the boundary at API 35: below it, `resolveService` is used and
 * its deprecated path is handled explicitly; from API 35, the callback is used.
 * (Platform metadata shows the callback also exists on API 34 and on API 33 with
 * Tiramisu extension 7, and `resolveService` is only marked deprecated at API 36 —
 * the conservative API-35 boundary is kept deliberately, since it matches the
 * plan and never loses a working path.)
 */
fun GoUsesServiceInfoCallback(goSdkInt: Int): Boolean = goSdkInt >= 35
