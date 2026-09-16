package com.glassstorm.phonemanager.domain.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Given/When/Then unit tests for the pure-JVM [HotspotStateMachine].
 *
 * The machine is the single source of truth for the AP lifecycle:
 * `IDLE -> STARTING -> ACTIVE -> STOPPING -> IDLE` plus an `ERROR` sink.
 * Every transition (including the illegal and idempotent ones) is locked here.
 */
class HotspotStateMachineTest {

    @Test
    fun `start request from IDLE moves to STARTING`() {
        // Given a fresh machine
        val GoMachine = HotspotStateMachine()

        // When a start is requested
        val GoResult = GoMachine.GoAccept(HotspotEvent.START_REQUESTED)

        // Then it enters STARTING
        assertThat(GoResult).isEqualTo(HotspotTransition.Moved(HotspotState.STARTING))
        assertThat(GoMachine.GoState).isEqualTo(HotspotState.STARTING)
    }

    @Test
    fun `started event from STARTING moves to ACTIVE`() {
        // Given a machine already starting
        val GoMachine = HotspotStateMachine()
        GoMachine.GoAccept(HotspotEvent.START_REQUESTED)

        // When the platform reports the AP is up
        val GoResult = GoMachine.GoAccept(HotspotEvent.STARTED)

        // Then it is ACTIVE
        assertThat(GoResult).isEqualTo(HotspotTransition.Moved(HotspotState.ACTIVE))
        assertThat(GoMachine.GoState).isEqualTo(HotspotState.ACTIVE)
    }

    @Test
    fun `start failure from STARTING enters ERROR with the typed cause`() {
        // Given a machine in STARTING
        val GoMachine = HotspotStateMachine()
        GoMachine.GoAccept(HotspotEvent.START_REQUESTED)

        // When the start fails because the location permission is missing
        val GoResult = GoMachine.GoAccept(HotspotEvent.START_FAILED, HotspotFailure.PermissionDenied)

        // Then the machine lands in ERROR — never ACTIVE — carrying the exact cause
        assertThat(GoResult).isEqualTo(
            HotspotTransition.Rejected(HotspotState.ERROR, HotspotFailure.PermissionDenied),
        )
        assertThat(GoMachine.GoState).isEqualTo(HotspotState.ERROR)
    }

    @Test
    fun `stop request from ACTIVE moves to STOPPING`() {
        // Given an ACTIVE AP
        val GoMachine = HotspotStateMachine()
        GoMachine.GoAccept(HotspotEvent.START_REQUESTED)
        GoMachine.GoAccept(HotspotEvent.STARTED)

        // When a stop is requested
        val GoResult = GoMachine.GoAccept(HotspotEvent.STOP_REQUESTED)

        // Then it enters STOPPING
        assertThat(GoResult).isEqualTo(HotspotTransition.Moved(HotspotState.STOPPING))
        assertThat(GoMachine.GoState).isEqualTo(HotspotState.STOPPING)
    }

    @Test
    fun `stopped event from STOPPING returns to IDLE`() {
        // Given a machine tearing down
        val GoMachine = HotspotStateMachine()
        GoMachine.GoAccept(HotspotEvent.START_REQUESTED)
        GoMachine.GoAccept(HotspotEvent.STARTED)
        GoMachine.GoAccept(HotspotEvent.STOP_REQUESTED)

        // When teardown completes
        val GoResult = GoMachine.GoAccept(HotspotEvent.STOPPED)

        // Then it is back to IDLE
        assertThat(GoResult).isEqualTo(HotspotTransition.Moved(HotspotState.IDLE))
        assertThat(GoMachine.GoState).isEqualTo(HotspotState.IDLE)
    }

    @Test
    fun `stop request from IDLE is an idempotent no-op`() {
        // Given a machine that never started
        val GoMachine = HotspotStateMachine()

        // When stop is requested
        val GoResult = GoMachine.GoAccept(HotspotEvent.STOP_REQUESTED)

        // Then it stays IDLE without an error
        assertThat(GoResult).isEqualTo(HotspotTransition.Moved(HotspotState.IDLE))
        assertThat(GoMachine.GoState).isEqualTo(HotspotState.IDLE)
    }

    @Test
    fun `stop request while STOPPING is an idempotent no-op`() {
        // Given a machine already stopping
        val GoMachine = HotspotStateMachine()
        GoMachine.GoAccept(HotspotEvent.START_REQUESTED)
        GoMachine.GoAccept(HotspotEvent.STARTED)
        GoMachine.GoAccept(HotspotEvent.STOP_REQUESTED)

        // When stop is requested again
        val GoResult = GoMachine.GoAccept(HotspotEvent.STOP_REQUESTED)

        // Then it remains STOPPING
        assertThat(GoResult).isEqualTo(HotspotTransition.Moved(HotspotState.STOPPING))
        assertThat(GoMachine.GoState).isEqualTo(HotspotState.STOPPING)
    }

    @Test
    fun `stop request from ERROR recovers to IDLE`() {
        // Given a machine in the ERROR sink
        val GoMachine = HotspotStateMachine()
        GoMachine.GoAccept(HotspotEvent.START_REQUESTED)
        GoMachine.GoAccept(HotspotEvent.START_FAILED, HotspotFailure.LocationServicesDisabled)

        // When stop is requested
        val GoResult = GoMachine.GoAccept(HotspotEvent.STOP_REQUESTED)

        // Then it clears back to IDLE
        assertThat(GoResult).isEqualTo(HotspotTransition.Moved(HotspotState.IDLE))
        assertThat(GoMachine.GoState).isEqualTo(HotspotState.IDLE)
    }

    @Test
    fun `retry start from ERROR moves back to STARTING`() {
        // Given a machine in ERROR after a failed start
        val GoMachine = HotspotStateMachine()
        GoMachine.GoAccept(HotspotEvent.START_REQUESTED)
        GoMachine.GoAccept(HotspotEvent.START_FAILED, HotspotFailure.PermissionDenied)

        // When the user retries
        val GoResult = GoMachine.GoAccept(HotspotEvent.START_REQUESTED)

        // Then it re-enters STARTING
        assertThat(GoResult).isEqualTo(HotspotTransition.Moved(HotspotState.STARTING))
        assertThat(GoMachine.GoState).isEqualTo(HotspotState.STARTING)
    }

    @Test
    fun `start request while ACTIVE is rejected and stays ACTIVE`() {
        // Given an ACTIVE AP
        val GoMachine = HotspotStateMachine()
        GoMachine.GoAccept(HotspotEvent.START_REQUESTED)
        GoMachine.GoAccept(HotspotEvent.STARTED)

        // When a second start is requested
        val GoResult = GoMachine.GoAccept(HotspotEvent.START_REQUESTED)

        // Then it is rejected as an illegal transition without leaving ACTIVE
        assertThat(GoResult).isEqualTo(
            HotspotTransition.Rejected(
                HotspotState.ACTIVE,
                HotspotFailure.IllegalTransition(HotspotState.ACTIVE, HotspotEvent.START_REQUESTED),
            ),
        )
        assertThat(GoMachine.GoState).isEqualTo(HotspotState.ACTIVE)
    }

    @Test
    fun `started event from STOPPING is rejected and stays STOPPING`() {
        // Given a machine tearing down
        val GoMachine = HotspotStateMachine()
        GoMachine.GoAccept(HotspotEvent.START_REQUESTED)
        GoMachine.GoAccept(HotspotEvent.STARTED)
        GoMachine.GoAccept(HotspotEvent.STOP_REQUESTED)

        // When a late started event arrives
        val GoResult = GoMachine.GoAccept(HotspotEvent.STARTED)

        // Then it is rejected and the state is unchanged
        assertThat(GoResult).isInstanceOf(HotspotTransition.Rejected::class.java)
        assertThat(GoMachine.GoState).isEqualTo(HotspotState.STOPPING)
    }

    @Test
    fun `started event from IDLE is rejected as illegal`() {
        // Given a fresh machine
        val GoMachine = HotspotStateMachine()

        // When a started event arrives out of order
        val GoResult = GoMachine.GoAccept(HotspotEvent.STARTED)

        // Then the typed illegal-transition failure is returned
        assertThat(GoResult).isEqualTo(
            HotspotTransition.Rejected(
                HotspotState.IDLE,
                HotspotFailure.IllegalTransition(HotspotState.IDLE, HotspotEvent.STARTED),
            ),
        )
    }

    @Test
    fun `stop request while STARTING moves to STOPPING so teardown can finish`() {
        // Given a machine mid-start
        val GoMachine = HotspotStateMachine()
        GoMachine.GoAccept(HotspotEvent.START_REQUESTED)

        // When a stop is requested before the AP is up
        val GoResult = GoMachine.GoAccept(HotspotEvent.STOP_REQUESTED)

        // Then it enters STOPPING rather than getting stuck
        assertThat(GoResult).isEqualTo(HotspotTransition.Moved(HotspotState.STOPPING))
        assertThat(GoMachine.GoState).isEqualTo(HotspotState.STOPPING)
    }
}
