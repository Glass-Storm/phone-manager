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
        val machine = HotspotStateMachine()

        // When a start is requested
        val result = machine.accept(HotspotEvent.START_REQUESTED)

        // Then it enters STARTING
        assertThat(result).isEqualTo(HotspotTransition.Moved(HotspotState.STARTING))
        assertThat(machine.state).isEqualTo(HotspotState.STARTING)
    }

    @Test
    fun `started event from STARTING moves to ACTIVE`() {
        // Given a machine already starting
        val machine = HotspotStateMachine()
        machine.accept(HotspotEvent.START_REQUESTED)

        // When the platform reports the AP is up
        val result = machine.accept(HotspotEvent.STARTED)

        // Then it is ACTIVE
        assertThat(result).isEqualTo(HotspotTransition.Moved(HotspotState.ACTIVE))
        assertThat(machine.state).isEqualTo(HotspotState.ACTIVE)
    }

    @Test
    fun `start failure from STARTING enters ERROR with the typed cause`() {
        // Given a machine in STARTING
        val machine = HotspotStateMachine()
        machine.accept(HotspotEvent.START_REQUESTED)

        // When the start fails because the location permission is missing
        val result = machine.accept(HotspotEvent.START_FAILED, HotspotFailure.PermissionDenied)

        // Then the machine lands in ERROR — never ACTIVE — carrying the exact cause
        assertThat(result).isEqualTo(
            HotspotTransition.Rejected(HotspotState.ERROR, HotspotFailure.PermissionDenied),
        )
        assertThat(machine.state).isEqualTo(HotspotState.ERROR)
    }

    @Test
    fun `stop request from ACTIVE moves to STOPPING`() {
        // Given an ACTIVE AP
        val machine = HotspotStateMachine()
        machine.accept(HotspotEvent.START_REQUESTED)
        machine.accept(HotspotEvent.STARTED)

        // When a stop is requested
        val result = machine.accept(HotspotEvent.STOP_REQUESTED)

        // Then it enters STOPPING
        assertThat(result).isEqualTo(HotspotTransition.Moved(HotspotState.STOPPING))
        assertThat(machine.state).isEqualTo(HotspotState.STOPPING)
    }

    @Test
    fun `stopped event from STOPPING returns to IDLE`() {
        // Given a machine tearing down
        val machine = HotspotStateMachine()
        machine.accept(HotspotEvent.START_REQUESTED)
        machine.accept(HotspotEvent.STARTED)
        machine.accept(HotspotEvent.STOP_REQUESTED)

        // When teardown completes
        val result = machine.accept(HotspotEvent.STOPPED)

        // Then it is back to IDLE
        assertThat(result).isEqualTo(HotspotTransition.Moved(HotspotState.IDLE))
        assertThat(machine.state).isEqualTo(HotspotState.IDLE)
    }

    @Test
    fun `stop request from IDLE is an idempotent no-op`() {
        // Given a machine that never started
        val machine = HotspotStateMachine()

        // When stop is requested
        val result = machine.accept(HotspotEvent.STOP_REQUESTED)

        // Then it stays IDLE without an error
        assertThat(result).isEqualTo(HotspotTransition.Moved(HotspotState.IDLE))
        assertThat(machine.state).isEqualTo(HotspotState.IDLE)
    }

    @Test
    fun `stop request while STOPPING is an idempotent no-op`() {
        // Given a machine already stopping
        val machine = HotspotStateMachine()
        machine.accept(HotspotEvent.START_REQUESTED)
        machine.accept(HotspotEvent.STARTED)
        machine.accept(HotspotEvent.STOP_REQUESTED)

        // When stop is requested again
        val result = machine.accept(HotspotEvent.STOP_REQUESTED)

        // Then it remains STOPPING
        assertThat(result).isEqualTo(HotspotTransition.Moved(HotspotState.STOPPING))
        assertThat(machine.state).isEqualTo(HotspotState.STOPPING)
    }

    @Test
    fun `stop request from ERROR recovers to IDLE`() {
        // Given a machine in the ERROR sink
        val machine = HotspotStateMachine()
        machine.accept(HotspotEvent.START_REQUESTED)
        machine.accept(HotspotEvent.START_FAILED, HotspotFailure.LocationServicesDisabled)

        // When stop is requested
        val result = machine.accept(HotspotEvent.STOP_REQUESTED)

        // Then it clears back to IDLE
        assertThat(result).isEqualTo(HotspotTransition.Moved(HotspotState.IDLE))
        assertThat(machine.state).isEqualTo(HotspotState.IDLE)
    }

    @Test
    fun `retry start from ERROR moves back to STARTING`() {
        // Given a machine in ERROR after a failed start
        val machine = HotspotStateMachine()
        machine.accept(HotspotEvent.START_REQUESTED)
        machine.accept(HotspotEvent.START_FAILED, HotspotFailure.PermissionDenied)

        // When the user retries
        val result = machine.accept(HotspotEvent.START_REQUESTED)

        // Then it re-enters STARTING
        assertThat(result).isEqualTo(HotspotTransition.Moved(HotspotState.STARTING))
        assertThat(machine.state).isEqualTo(HotspotState.STARTING)
    }

    @Test
    fun `start request while ACTIVE is rejected and stays ACTIVE`() {
        // Given an ACTIVE AP
        val machine = HotspotStateMachine()
        machine.accept(HotspotEvent.START_REQUESTED)
        machine.accept(HotspotEvent.STARTED)

        // When a second start is requested
        val result = machine.accept(HotspotEvent.START_REQUESTED)

        // Then it is rejected as an illegal transition without leaving ACTIVE
        assertThat(result).isEqualTo(
            HotspotTransition.Rejected(
                HotspotState.ACTIVE,
                HotspotFailure.IllegalTransition(HotspotState.ACTIVE, HotspotEvent.START_REQUESTED),
            ),
        )
        assertThat(machine.state).isEqualTo(HotspotState.ACTIVE)
    }

    @Test
    fun `started event from STOPPING is rejected and stays STOPPING`() {
        // Given a machine tearing down
        val machine = HotspotStateMachine()
        machine.accept(HotspotEvent.START_REQUESTED)
        machine.accept(HotspotEvent.STARTED)
        machine.accept(HotspotEvent.STOP_REQUESTED)

        // When a late started event arrives
        val result = machine.accept(HotspotEvent.STARTED)

        // Then it is rejected and the state is unchanged
        assertThat(result).isInstanceOf(HotspotTransition.Rejected::class.java)
        assertThat(machine.state).isEqualTo(HotspotState.STOPPING)
    }

    @Test
    fun `started event from IDLE is rejected as illegal`() {
        // Given a fresh machine
        val machine = HotspotStateMachine()

        // When a started event arrives out of order
        val result = machine.accept(HotspotEvent.STARTED)

        // Then the typed illegal-transition failure is returned
        assertThat(result).isEqualTo(
            HotspotTransition.Rejected(
                HotspotState.IDLE,
                HotspotFailure.IllegalTransition(HotspotState.IDLE, HotspotEvent.STARTED),
            ),
        )
    }

    @Test
    fun `stop request while STARTING moves to STOPPING so teardown can finish`() {
        // Given a machine mid-start
        val machine = HotspotStateMachine()
        machine.accept(HotspotEvent.START_REQUESTED)

        // When a stop is requested before the AP is up
        val result = machine.accept(HotspotEvent.STOP_REQUESTED)

        // Then it enters STOPPING rather than getting stuck
        assertThat(result).isEqualTo(HotspotTransition.Moved(HotspotState.STOPPING))
        assertThat(machine.state).isEqualTo(HotspotState.STOPPING)
    }
}
