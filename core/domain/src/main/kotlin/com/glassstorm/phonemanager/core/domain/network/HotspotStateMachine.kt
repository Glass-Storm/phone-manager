package com.glassstorm.phonemanager.core.domain.network

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
    data class StartFailed(
        val reason: String,
    ) : HotspotFailure

    /** The event is not valid for the current state. */
    data class IllegalTransition(
        val from: HotspotState,
        val event: HotspotEvent,
    ) : HotspotFailure
}

/** Outcome of feeding one event into the machine. */
sealed interface HotspotTransition {
    /** The machine changed to (or idempotently remained in) [state]. */
    data class Moved(
        val state: HotspotState,
    ) : HotspotTransition

    /** The event was refused; the machine stayed in [state] and [failure] says why. */
    data class Rejected(
        val state: HotspotState,
        val failure: HotspotFailure,
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
    var state: HotspotState = HotspotState.IDLE
        private set

    /**
     * Feed [event] into the machine.
     *
     * [failure] is only consulted for [HotspotEvent.START_FAILED]; when omitted a
     * generic [HotspotFailure.StartFailed] is recorded.
     */
    fun accept(
        event: HotspotEvent,
        failure: HotspotFailure? = null,
    ): HotspotTransition =
        when (state) {
            HotspotState.IDLE -> fromIdle(event, failure)
            HotspotState.STARTING -> fromStarting(event, failure)
            HotspotState.ACTIVE -> fromActive(event, failure)
            HotspotState.STOPPING -> fromStopping(event, failure)
            HotspotState.ERROR -> fromError(event, failure)
        }

    private fun fromIdle(
        event: HotspotEvent,
        failure: HotspotFailure?,
    ): HotspotTransition =
        when (event) {
            HotspotEvent.START_REQUESTED -> moveTo(HotspotState.STARTING)
            HotspotEvent.STOP_REQUESTED -> moveTo(HotspotState.IDLE)
            HotspotEvent.STARTED, HotspotEvent.STOPPED, HotspotEvent.START_FAILED ->
                reject(event, failure)
        }

    private fun fromStarting(
        event: HotspotEvent,
        failure: HotspotFailure?,
    ): HotspotTransition =
        when (event) {
            HotspotEvent.STARTED -> moveTo(HotspotState.ACTIVE)
            HotspotEvent.START_FAILED -> {
                val failure = failure ?: HotspotFailure.StartFailed("start failed")
                state = HotspotState.ERROR
                HotspotTransition.Rejected(HotspotState.ERROR, failure)
            }
            HotspotEvent.STOP_REQUESTED -> moveTo(HotspotState.STOPPING)
            HotspotEvent.START_REQUESTED, HotspotEvent.STOPPED -> reject(event, failure)
        }

    private fun fromActive(
        event: HotspotEvent,
        failure: HotspotFailure?,
    ): HotspotTransition =
        when (event) {
            HotspotEvent.STOP_REQUESTED -> moveTo(HotspotState.STOPPING)
            HotspotEvent.START_REQUESTED, HotspotEvent.STARTED,
            HotspotEvent.START_FAILED, HotspotEvent.STOPPED,
            -> reject(event, failure)
        }

    private fun fromStopping(
        event: HotspotEvent,
        failure: HotspotFailure?,
    ): HotspotTransition =
        when (event) {
            HotspotEvent.STOPPED -> moveTo(HotspotState.IDLE)
            HotspotEvent.STOP_REQUESTED -> moveTo(HotspotState.STOPPING)
            HotspotEvent.START_REQUESTED, HotspotEvent.STARTED, HotspotEvent.START_FAILED ->
                reject(event, failure)
        }

    private fun fromError(
        event: HotspotEvent,
        failure: HotspotFailure?,
    ): HotspotTransition =
        when (event) {
            HotspotEvent.START_REQUESTED -> moveTo(HotspotState.STARTING)
            HotspotEvent.STOP_REQUESTED -> moveTo(HotspotState.IDLE)
            HotspotEvent.STARTED, HotspotEvent.STOPPED, HotspotEvent.START_FAILED ->
                reject(event, failure)
        }

    private fun moveTo(next: HotspotState): HotspotTransition {
        state = next
        return HotspotTransition.Moved(next)
    }

    private fun reject(
        event: HotspotEvent,
        failure: HotspotFailure?,
    ): HotspotTransition {
        val failure = failure ?: HotspotFailure.IllegalTransition(state, event)
        return HotspotTransition.Rejected(state, failure)
    }
}

/**
 * Raised by a [com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController]
 * when the access point cannot be started.
 *
 * [failure] carries the typed cause so callers can branch on it exhaustively.
 */
class HotspotUnavailableException(
    val failure: HotspotFailure,
) : Exception("hotspot unavailable: $failure")
