package com.glassstorm.phonemanager.domain.network

/**
 * Lifecycle of the phone-hosted access point.
 *
 * Pure JVM: no Android types, so the whole lifecycle is testable on a plain JVM.
 * `IDLE -> STARTING -> ACTIVE -> STOPPING -> IDLE`, with `ERROR` reachable from
 * any failure and recoverable by either `START_REQUESTED` or `STOP_REQUESTED`.
 */
enum class HotspotState {
    IDLE,
    STARTING,
    ACTIVE,
    STOPPING,
    ERROR,
}

/** Inputs the adapter feeds into [HotspotStateMachine]. */
enum class HotspotEvent {
    START_REQUESTED,
    STARTED,
    START_FAILED,
    STOP_REQUESTED,
    STOPPED,
}

/** Typed reasons a hotspot action could not proceed. Never a bare string. */
sealed interface HotspotFailure {
    /** The runtime Wi-Fi permission was not granted by the user. */
    data object PermissionDenied : HotspotFailure

    /** Location services are switched off; API 29 requires them for LocalOnlyHotspot. */
    data object LocationServicesDisabled : HotspotFailure

    /** The platform reported a start failure (OEM refusal, channel unavailable, ...). */
    data class StartFailed(val GoReason: String) : HotspotFailure

    /** The event is not valid for the current state. */
    data class IllegalTransition(
        val GoFrom: HotspotState,
        val GoEvent: HotspotEvent,
    ) : HotspotFailure
}

/** Outcome of feeding one event into the machine. */
sealed interface HotspotTransition {
    /** The machine changed to (or idempotently remained in) [GoState]. */
    data class Moved(val GoState: HotspotState) : HotspotTransition

    /** The event was refused; the machine stayed in [GoState] and [GoFailure] says why. */
    data class Rejected(
        val GoState: HotspotState,
        val GoFailure: HotspotFailure,
    ) : HotspotTransition
}

/**
 * Deterministic hotspot lifecycle state machine.
 *
 * Not thread-safe by design: the adapter owns it and drives it from a single
 * serialized path. Every `when` over [HotspotState]/[HotspotEvent] is exhaustive,
 * so a newly added state or event becomes a compile error rather than a silent
 * fall-through.
 */
class HotspotStateMachine {

    var GoState: HotspotState = HotspotState.IDLE
        private set

    /**
     * Feed [event] into the machine.
     *
     * [failure] is only consulted for [HotspotEvent.START_FAILED]; when omitted a
     * generic [HotspotFailure.StartFailed] is recorded.
     */
    fun GoAccept(event: HotspotEvent, failure: HotspotFailure? = null): HotspotTransition =
        when (GoState) {
            HotspotState.IDLE -> GoFromIdle(event, failure)
            HotspotState.STARTING -> GoFromStarting(event, failure)
            HotspotState.ACTIVE -> GoFromActive(event, failure)
            HotspotState.STOPPING -> GoFromStopping(event, failure)
            HotspotState.ERROR -> GoFromError(event, failure)
        }

    private fun GoFromIdle(event: HotspotEvent, failure: HotspotFailure?): HotspotTransition =
        when (event) {
            HotspotEvent.START_REQUESTED -> GoMoveTo(HotspotState.STARTING)
            HotspotEvent.STOP_REQUESTED -> GoMoveTo(HotspotState.IDLE)
            HotspotEvent.STARTED, HotspotEvent.STOPPED, HotspotEvent.START_FAILED ->
                GoReject(event, failure)
        }

    private fun GoFromStarting(event: HotspotEvent, failure: HotspotFailure?): HotspotTransition =
        when (event) {
            HotspotEvent.STARTED -> GoMoveTo(HotspotState.ACTIVE)
            HotspotEvent.START_FAILED -> {
                val GoFailure = failure ?: HotspotFailure.StartFailed("start failed")
                GoState = HotspotState.ERROR
                HotspotTransition.Rejected(HotspotState.ERROR, GoFailure)
            }
            HotspotEvent.STOP_REQUESTED -> GoMoveTo(HotspotState.STOPPING)
            HotspotEvent.START_REQUESTED, HotspotEvent.STOPPED -> GoReject(event, failure)
        }

    private fun GoFromActive(event: HotspotEvent, failure: HotspotFailure?): HotspotTransition =
        when (event) {
            HotspotEvent.STOP_REQUESTED -> GoMoveTo(HotspotState.STOPPING)
            HotspotEvent.START_REQUESTED, HotspotEvent.STARTED,
            HotspotEvent.START_FAILED, HotspotEvent.STOPPED,
            -> GoReject(event, failure)
        }

    private fun GoFromStopping(event: HotspotEvent, failure: HotspotFailure?): HotspotTransition =
        when (event) {
            HotspotEvent.STOPPED -> GoMoveTo(HotspotState.IDLE)
            HotspotEvent.STOP_REQUESTED -> GoMoveTo(HotspotState.STOPPING)
            HotspotEvent.START_REQUESTED, HotspotEvent.STARTED, HotspotEvent.START_FAILED ->
                GoReject(event, failure)
        }

    private fun GoFromError(event: HotspotEvent, failure: HotspotFailure?): HotspotTransition =
        when (event) {
            HotspotEvent.START_REQUESTED -> GoMoveTo(HotspotState.STARTING)
            HotspotEvent.STOP_REQUESTED -> GoMoveTo(HotspotState.IDLE)
            HotspotEvent.STARTED, HotspotEvent.STOPPED, HotspotEvent.START_FAILED ->
                GoReject(event, failure)
        }

    private fun GoMoveTo(next: HotspotState): HotspotTransition {
        GoState = next
        return HotspotTransition.Moved(next)
    }

    private fun GoReject(event: HotspotEvent, failure: HotspotFailure?): HotspotTransition {
        val GoFailure = failure ?: HotspotFailure.IllegalTransition(GoState, event)
        return HotspotTransition.Rejected(GoState, GoFailure)
    }
}

/**
 * Raised by a [com.glassstorm.phonemanager.domain.adapter.network.HotspotController]
 * when the access point cannot be started.
 *
 * [GoFailure] carries the typed cause so callers can branch on it exhaustively.
 */
class HotspotUnavailableException(
    val GoFailure: HotspotFailure,
) : Exception("hotspot unavailable: $GoFailure")
