package com.glassstorm.phonemanager.core.domain.network

import com.glassstorm.phonemanager.core.model.PeerAddress
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Given/When/Then unit tests for the pure-JVM [DiscoveryStateMachine].
 *
 * The machine is the single source of truth for the mDNS advertisement/resolution
 * lifecycle: `IDLE -> ADVERTISING -> RESOLVING -> (RESOLVED | FALLBACK | TIMEOUT)`.
 * The direct-IP gateway fallback is a NORMAL terminal state, not an error, and is
 * locked here alongside the timeout branch.
 */
class DiscoveryStateMachineTest {
    private val gateway = PeerAddress("192.168.43.1", 9000, PeerAddress.SOURCE_GATEWAY)
    private val mdns = PeerAddress("192.168.43.7", 9000, PeerAddress.SOURCE_MDNS)

    @Test
    fun `start advertise from IDLE moves to ADVERTISING`() {
        // Given a fresh machine
        val machine = DiscoveryStateMachine(gateway)

        // When advertisement is requested
        val result = machine.accept(DiscoveryEvent.START_ADVERTISE)

        // Then it enters ADVERTISING
        assertThat(result).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.ADVERTISING))
        assertThat(machine.state).isEqualTo(DiscoveryState.ADVERTISING)
    }

    @Test
    fun `registration confirmation keeps the machine ADVERTISING`() {
        // Given a machine mid-advertisement
        val machine = DiscoveryStateMachine(gateway)
        machine.accept(DiscoveryEvent.START_ADVERTISE)

        // When the platform confirms the service is registered
        val result = machine.accept(DiscoveryEvent.ADVERTISE_REGISTERED)

        // Then it stays ADVERTISING (registration is a confirmation, not a new state)
        assertThat(result).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.ADVERTISING))
        assertThat(machine.state).isEqualTo(DiscoveryState.ADVERTISING)
    }

    @Test
    fun `advertise failure returns to IDLE with the typed cause`() {
        // Given a machine mid-advertisement
        val machine = DiscoveryStateMachine(gateway)
        machine.accept(DiscoveryEvent.START_ADVERTISE)

        // When registration fails
        val result =
            machine.accept(
                DiscoveryEvent.ADVERTISE_FAILED,
                DiscoveryFailure.AdvertiseFailed("registerService failed code=3"),
            )

        // Then it falls back to IDLE carrying the cause
        assertThat(result).isEqualTo(
            DiscoveryTransition.Rejected(
                DiscoveryState.IDLE,
                DiscoveryFailure.AdvertiseFailed("registerService failed code=3"),
            ),
        )
        assertThat(machine.state).isEqualTo(DiscoveryState.IDLE)
        assertThat(machine.lastFailure)
            .isEqualTo(DiscoveryFailure.AdvertiseFailed("registerService failed code=3"))
    }

    @Test
    fun `start resolve from ADVERTISING moves to RESOLVING`() {
        // Given an advertised service
        val machine = DiscoveryStateMachine(gateway)
        machine.accept(DiscoveryEvent.START_ADVERTISE)

        // When resolution starts
        val result = machine.accept(DiscoveryEvent.START_RESOLVE)

        // Then it enters RESOLVING
        assertThat(result).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.RESOLVING))
        assertThat(machine.state).isEqualTo(DiscoveryState.RESOLVING)
    }

    @Test
    fun `mdns resolution from RESOLVING moves to RESOLVED`() {
        // Given a machine resolving
        val machine = DiscoveryStateMachine(gateway)
        machine.accept(DiscoveryEvent.START_ADVERTISE)
        machine.accept(DiscoveryEvent.START_RESOLVE)

        // When mDNS resolves a peer
        val result = machine.accept(DiscoveryEvent.MDNS_RESOLVED)

        // Then it is RESOLVED
        assertThat(result).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.RESOLVED))
        assertThat(machine.state).isEqualTo(DiscoveryState.RESOLVED)
    }

    @Test
    fun `mdns miss with a configured gateway falls back to the gateway`() {
        // Given a machine resolving with a gateway configured
        val machine = DiscoveryStateMachine(gateway)
        machine.accept(DiscoveryEvent.START_ADVERTISE)
        machine.accept(DiscoveryEvent.START_RESOLVE)

        // When mDNS resolution fails
        val result =
            machine.accept(
                DiscoveryEvent.MDNS_FAILED,
                DiscoveryFailure.ResolveFailed("onResolveFailed code=0"),
            )

        // Then the fallback is a normal terminal state (not an error)
        assertThat(result).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.FALLBACK))
        assertThat(machine.state).isEqualTo(DiscoveryState.FALLBACK)
        assertThat(machine.lastFailure)
            .isEqualTo(DiscoveryFailure.ResolveFailed("onResolveFailed code=0"))
    }

    @Test
    fun `resolve timeout with a configured gateway falls back to the gateway`() {
        // Given a machine resolving with a gateway configured
        val machine = DiscoveryStateMachine(gateway)
        machine.accept(DiscoveryEvent.START_ADVERTISE)
        machine.accept(DiscoveryEvent.START_RESOLVE)

        // When the resolution window elapses
        val result = machine.accept(DiscoveryEvent.RESOLVE_TIMEOUT, DiscoveryFailure.ResolveTimedOut)

        // Then it still reaches the gateway fallback
        assertThat(result).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.FALLBACK))
        assertThat(machine.state).isEqualTo(DiscoveryState.FALLBACK)
    }

    @Test
    fun `mdns miss without a gateway reaches TIMEOUT`() {
        // Given a machine with NO gateway fallback configured
        val machine = DiscoveryStateMachine(gatewayFallback = null)
        machine.accept(DiscoveryEvent.START_ADVERTISE)
        machine.accept(DiscoveryEvent.START_RESOLVE)

        // When mDNS resolution fails
        val result = machine.accept(DiscoveryEvent.MDNS_FAILED, DiscoveryFailure.ResolveFailed("no peer"))

        // Then there is nothing to fall back to, so it times out
        assertThat(result).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.TIMEOUT))
        assertThat(machine.state).isEqualTo(DiscoveryState.TIMEOUT)
    }

    @Test
    fun `resolve timeout without a gateway reaches TIMEOUT`() {
        // Given a machine with NO gateway fallback configured
        val machine = DiscoveryStateMachine(gatewayFallback = null)
        machine.accept(DiscoveryEvent.START_ADVERTISE)
        machine.accept(DiscoveryEvent.START_RESOLVE)

        // When the window elapses
        val result = machine.accept(DiscoveryEvent.RESOLVE_TIMEOUT, DiscoveryFailure.ResolveTimedOut)

        // Then it is TIMEOUT
        assertThat(result).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.TIMEOUT))
        assertThat(machine.state).isEqualTo(DiscoveryState.TIMEOUT)
    }

    @Test
    fun `stop request from RESOLVING returns to IDLE`() {
        // Given a machine resolving
        val machine = DiscoveryStateMachine(gateway)
        machine.accept(DiscoveryEvent.START_ADVERTISE)
        machine.accept(DiscoveryEvent.START_RESOLVE)

        // When teardown is requested
        val result = machine.accept(DiscoveryEvent.STOP_REQUESTED)

        // Then it is IDLE
        assertThat(result).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.IDLE))
        assertThat(machine.state).isEqualTo(DiscoveryState.IDLE)
    }

    @Test
    fun `stop request from the gateway fallback returns to IDLE`() {
        // Given a machine already on the fallback
        val machine = DiscoveryStateMachine(gateway)
        machine.accept(DiscoveryEvent.START_ADVERTISE)
        machine.accept(DiscoveryEvent.START_RESOLVE)
        machine.accept(DiscoveryEvent.MDNS_FAILED, DiscoveryFailure.ResolveFailed("no peer"))

        // When teardown is requested
        val result = machine.accept(DiscoveryEvent.STOP_REQUESTED)

        // Then it is IDLE
        assertThat(result).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.IDLE))
        assertThat(machine.state).isEqualTo(DiscoveryState.IDLE)
    }

    @Test
    fun `re-resolving from FALLBACK moves back to RESOLVING`() {
        // Given a machine sitting on the gateway fallback
        val machine = DiscoveryStateMachine(gateway)
        machine.accept(DiscoveryEvent.START_ADVERTISE)
        machine.accept(DiscoveryEvent.START_RESOLVE)
        machine.accept(DiscoveryEvent.MDNS_FAILED, DiscoveryFailure.ResolveFailed("no peer"))

        // When a retry is requested
        val result = machine.accept(DiscoveryEvent.START_RESOLVE)

        // Then it re-enters RESOLVING so a later mDNS hit can win
        assertThat(result).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.RESOLVING))
        assertThat(machine.state).isEqualTo(DiscoveryState.RESOLVING)
    }

    @Test
    fun `re-resolving from TIMEOUT moves back to RESOLVING`() {
        // Given a machine that timed out
        val machine = DiscoveryStateMachine(gatewayFallback = null)
        machine.accept(DiscoveryEvent.START_ADVERTISE)
        machine.accept(DiscoveryEvent.START_RESOLVE)
        machine.accept(DiscoveryEvent.RESOLVE_TIMEOUT, DiscoveryFailure.ResolveTimedOut)

        // When a retry is requested
        val result = machine.accept(DiscoveryEvent.START_RESOLVE)

        // Then it re-enters RESOLVING
        assertThat(result).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.RESOLVING))
        assertThat(machine.state).isEqualTo(DiscoveryState.RESOLVING)
    }

    @Test
    fun `mdns resolved from IDLE is rejected as illegal`() {
        // Given a machine that never started
        val machine = DiscoveryStateMachine(gateway)

        // When a resolved event arrives out of order
        val result = machine.accept(DiscoveryEvent.MDNS_RESOLVED)

        // Then it is refused with the typed illegal-transition failure
        assertThat(result).isEqualTo(
            DiscoveryTransition.Rejected(
                DiscoveryState.IDLE,
                DiscoveryFailure.IllegalTransition(DiscoveryState.IDLE, DiscoveryEvent.MDNS_RESOLVED),
            ),
        )
    }

    @Test
    fun `select peer prefers mdns when both candidates exist`() {
        // Given both an mDNS hit and a configured gateway
        val machine = DiscoveryStateMachine(gateway)

        // When a peer is selected
        val selection = machine.selectPeer(mdns, gateway)

        // Then mDNS wins
        assertThat(selection).isEqualTo(DiscoverySelection.Selected(mdns))
    }

    @Test
    fun `select peer falls back to the gateway on an mdns miss`() {
        // Given an mDNS miss and a configured gateway
        val machine = DiscoveryStateMachine(gateway)

        // When a peer is selected
        val selection = machine.selectPeer(mdns = null, gateway = gateway)

        // Then the gateway peer is selected (source = gateway)
        assertThat(selection).isEqualTo(DiscoverySelection.Selected(gateway))
    }

    @Test
    fun `select peer reports NoPeer when neither candidate exists`() {
        // Given neither an mDNS hit nor a gateway
        val machine = DiscoveryStateMachine(gatewayFallback = null)

        // When a peer is selected
        val selection = machine.selectPeer(mdns = null, gateway = null)

        // Then there is nothing to connect to
        assertThat(selection).isEqualTo(DiscoverySelection.NoPeer)
    }

    @Test
    fun `multicast lock is required below api 33 and on api 33 without T-ext 7`() {
        // Android 12L and below always need the lock
        assertThat(needsMulticastLock(29) { error("must not query SdkExtensions below 33") }).isTrue()
        assertThat(needsMulticastLock(32) { error("must not query SdkExtensions below 33") }).isTrue()

        // Android 13 needs it only while the Tiramisu extension is below 7
        assertThat(needsMulticastLock(33) { 0 }).isTrue()
        assertThat(needsMulticastLock(33) { 6 }).isTrue()
        assertThat(needsMulticastLock(33) { 7 }).isFalse()

        // Android 14+ never needs the app-held lock
        assertThat(needsMulticastLock(34) { error("must not query SdkExtensions above 33") }).isFalse()
        assertThat(needsMulticastLock(36) { error("must not query SdkExtensions above 33") }).isFalse()
    }

    @Test
    fun `multicast lock decision on api 33 reads the Tiramisu extension version`() {
        // Given a counter proving the extension is actually consulted on API 33 only
        var queries = 0

        // When the decision is made on API 33
        needsMulticastLock(33) {
            queries += 1
            7
        }

        // Then the extension version was read exactly once
        assertThat(queries).isEqualTo(1)
    }

    @Test
    fun `service info callback is used from api 35`() {
        // The deprecated resolveService path is used up to and including API 34
        assertThat(usesServiceInfoCallback(29)).isFalse()
        assertThat(usesServiceInfoCallback(33)).isFalse()
        assertThat(usesServiceInfoCallback(34)).isFalse()

        // registerServiceInfoCallback takes over from API 35
        assertThat(usesServiceInfoCallback(35)).isTrue()
        assertThat(usesServiceInfoCallback(36)).isTrue()
    }
}
