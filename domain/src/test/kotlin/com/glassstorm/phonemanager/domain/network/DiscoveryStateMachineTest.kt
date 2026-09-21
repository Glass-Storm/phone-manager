package com.glassstorm.phonemanager.domain.network

import com.glassstorm.phonemanager.domain.dto.PeerAddress
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
    private val GoGateway = PeerAddress("192.168.43.1", 9000, PeerAddress.GoSourceGateway)
    private val GoMdns = PeerAddress("192.168.43.7", 9000, PeerAddress.GoSourceMdns)

    @Test
    fun `start advertise from IDLE moves to ADVERTISING`() {
        // Given a fresh machine
        val GoMachine = DiscoveryStateMachine(GoGateway)

        // When advertisement is requested
        val GoResult = GoMachine.GoAccept(DiscoveryEvent.START_ADVERTISE)

        // Then it enters ADVERTISING
        assertThat(GoResult).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.ADVERTISING))
        assertThat(GoMachine.GoState).isEqualTo(DiscoveryState.ADVERTISING)
    }

    @Test
    fun `registration confirmation keeps the machine ADVERTISING`() {
        // Given a machine mid-advertisement
        val GoMachine = DiscoveryStateMachine(GoGateway)
        GoMachine.GoAccept(DiscoveryEvent.START_ADVERTISE)

        // When the platform confirms the service is registered
        val GoResult = GoMachine.GoAccept(DiscoveryEvent.ADVERTISE_REGISTERED)

        // Then it stays ADVERTISING (registration is a confirmation, not a new state)
        assertThat(GoResult).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.ADVERTISING))
        assertThat(GoMachine.GoState).isEqualTo(DiscoveryState.ADVERTISING)
    }

    @Test
    fun `advertise failure returns to IDLE with the typed cause`() {
        // Given a machine mid-advertisement
        val GoMachine = DiscoveryStateMachine(GoGateway)
        GoMachine.GoAccept(DiscoveryEvent.START_ADVERTISE)

        // When registration fails
        val GoResult =
            GoMachine.GoAccept(
                DiscoveryEvent.ADVERTISE_FAILED,
                DiscoveryFailure.AdvertiseFailed("registerService failed code=3"),
            )

        // Then it falls back to IDLE carrying the cause
        assertThat(GoResult).isEqualTo(
            DiscoveryTransition.Rejected(
                DiscoveryState.IDLE,
                DiscoveryFailure.AdvertiseFailed("registerService failed code=3"),
            ),
        )
        assertThat(GoMachine.GoState).isEqualTo(DiscoveryState.IDLE)
        assertThat(GoMachine.GoLastFailure)
            .isEqualTo(DiscoveryFailure.AdvertiseFailed("registerService failed code=3"))
    }

    @Test
    fun `start resolve from ADVERTISING moves to RESOLVING`() {
        // Given an advertised service
        val GoMachine = DiscoveryStateMachine(GoGateway)
        GoMachine.GoAccept(DiscoveryEvent.START_ADVERTISE)

        // When resolution starts
        val GoResult = GoMachine.GoAccept(DiscoveryEvent.START_RESOLVE)

        // Then it enters RESOLVING
        assertThat(GoResult).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.RESOLVING))
        assertThat(GoMachine.GoState).isEqualTo(DiscoveryState.RESOLVING)
    }

    @Test
    fun `mdns resolution from RESOLVING moves to RESOLVED`() {
        // Given a machine resolving
        val GoMachine = DiscoveryStateMachine(GoGateway)
        GoMachine.GoAccept(DiscoveryEvent.START_ADVERTISE)
        GoMachine.GoAccept(DiscoveryEvent.START_RESOLVE)

        // When mDNS resolves a peer
        val GoResult = GoMachine.GoAccept(DiscoveryEvent.MDNS_RESOLVED)

        // Then it is RESOLVED
        assertThat(GoResult).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.RESOLVED))
        assertThat(GoMachine.GoState).isEqualTo(DiscoveryState.RESOLVED)
    }

    @Test
    fun `mdns miss with a configured gateway falls back to the gateway`() {
        // Given a machine resolving with a gateway configured
        val GoMachine = DiscoveryStateMachine(GoGateway)
        GoMachine.GoAccept(DiscoveryEvent.START_ADVERTISE)
        GoMachine.GoAccept(DiscoveryEvent.START_RESOLVE)

        // When mDNS resolution fails
        val GoResult =
            GoMachine.GoAccept(
                DiscoveryEvent.MDNS_FAILED,
                DiscoveryFailure.ResolveFailed("onResolveFailed code=0"),
            )

        // Then the fallback is a normal terminal state (not an error)
        assertThat(GoResult).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.FALLBACK))
        assertThat(GoMachine.GoState).isEqualTo(DiscoveryState.FALLBACK)
        assertThat(GoMachine.GoLastFailure)
            .isEqualTo(DiscoveryFailure.ResolveFailed("onResolveFailed code=0"))
    }

    @Test
    fun `resolve timeout with a configured gateway falls back to the gateway`() {
        // Given a machine resolving with a gateway configured
        val GoMachine = DiscoveryStateMachine(GoGateway)
        GoMachine.GoAccept(DiscoveryEvent.START_ADVERTISE)
        GoMachine.GoAccept(DiscoveryEvent.START_RESOLVE)

        // When the resolution window elapses
        val GoResult = GoMachine.GoAccept(DiscoveryEvent.RESOLVE_TIMEOUT, DiscoveryFailure.ResolveTimedOut)

        // Then it still reaches the gateway fallback
        assertThat(GoResult).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.FALLBACK))
        assertThat(GoMachine.GoState).isEqualTo(DiscoveryState.FALLBACK)
    }

    @Test
    fun `mdns miss without a gateway reaches TIMEOUT`() {
        // Given a machine with NO gateway fallback configured
        val GoMachine = DiscoveryStateMachine(GoGatewayFallback = null)
        GoMachine.GoAccept(DiscoveryEvent.START_ADVERTISE)
        GoMachine.GoAccept(DiscoveryEvent.START_RESOLVE)

        // When mDNS resolution fails
        val GoResult = GoMachine.GoAccept(DiscoveryEvent.MDNS_FAILED, DiscoveryFailure.ResolveFailed("no peer"))

        // Then there is nothing to fall back to, so it times out
        assertThat(GoResult).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.TIMEOUT))
        assertThat(GoMachine.GoState).isEqualTo(DiscoveryState.TIMEOUT)
    }

    @Test
    fun `resolve timeout without a gateway reaches TIMEOUT`() {
        // Given a machine with NO gateway fallback configured
        val GoMachine = DiscoveryStateMachine(GoGatewayFallback = null)
        GoMachine.GoAccept(DiscoveryEvent.START_ADVERTISE)
        GoMachine.GoAccept(DiscoveryEvent.START_RESOLVE)

        // When the window elapses
        val GoResult = GoMachine.GoAccept(DiscoveryEvent.RESOLVE_TIMEOUT, DiscoveryFailure.ResolveTimedOut)

        // Then it is TIMEOUT
        assertThat(GoResult).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.TIMEOUT))
        assertThat(GoMachine.GoState).isEqualTo(DiscoveryState.TIMEOUT)
    }

    @Test
    fun `stop request from RESOLVING returns to IDLE`() {
        // Given a machine resolving
        val GoMachine = DiscoveryStateMachine(GoGateway)
        GoMachine.GoAccept(DiscoveryEvent.START_ADVERTISE)
        GoMachine.GoAccept(DiscoveryEvent.START_RESOLVE)

        // When teardown is requested
        val GoResult = GoMachine.GoAccept(DiscoveryEvent.STOP_REQUESTED)

        // Then it is IDLE
        assertThat(GoResult).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.IDLE))
        assertThat(GoMachine.GoState).isEqualTo(DiscoveryState.IDLE)
    }

    @Test
    fun `stop request from the gateway fallback returns to IDLE`() {
        // Given a machine already on the fallback
        val GoMachine = DiscoveryStateMachine(GoGateway)
        GoMachine.GoAccept(DiscoveryEvent.START_ADVERTISE)
        GoMachine.GoAccept(DiscoveryEvent.START_RESOLVE)
        GoMachine.GoAccept(DiscoveryEvent.MDNS_FAILED, DiscoveryFailure.ResolveFailed("no peer"))

        // When teardown is requested
        val GoResult = GoMachine.GoAccept(DiscoveryEvent.STOP_REQUESTED)

        // Then it is IDLE
        assertThat(GoResult).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.IDLE))
        assertThat(GoMachine.GoState).isEqualTo(DiscoveryState.IDLE)
    }

    @Test
    fun `re-resolving from FALLBACK moves back to RESOLVING`() {
        // Given a machine sitting on the gateway fallback
        val GoMachine = DiscoveryStateMachine(GoGateway)
        GoMachine.GoAccept(DiscoveryEvent.START_ADVERTISE)
        GoMachine.GoAccept(DiscoveryEvent.START_RESOLVE)
        GoMachine.GoAccept(DiscoveryEvent.MDNS_FAILED, DiscoveryFailure.ResolveFailed("no peer"))

        // When a retry is requested
        val GoResult = GoMachine.GoAccept(DiscoveryEvent.START_RESOLVE)

        // Then it re-enters RESOLVING so a later mDNS hit can win
        assertThat(GoResult).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.RESOLVING))
        assertThat(GoMachine.GoState).isEqualTo(DiscoveryState.RESOLVING)
    }

    @Test
    fun `re-resolving from TIMEOUT moves back to RESOLVING`() {
        // Given a machine that timed out
        val GoMachine = DiscoveryStateMachine(GoGatewayFallback = null)
        GoMachine.GoAccept(DiscoveryEvent.START_ADVERTISE)
        GoMachine.GoAccept(DiscoveryEvent.START_RESOLVE)
        GoMachine.GoAccept(DiscoveryEvent.RESOLVE_TIMEOUT, DiscoveryFailure.ResolveTimedOut)

        // When a retry is requested
        val GoResult = GoMachine.GoAccept(DiscoveryEvent.START_RESOLVE)

        // Then it re-enters RESOLVING
        assertThat(GoResult).isEqualTo(DiscoveryTransition.Moved(DiscoveryState.RESOLVING))
        assertThat(GoMachine.GoState).isEqualTo(DiscoveryState.RESOLVING)
    }

    @Test
    fun `mdns resolved from IDLE is rejected as illegal`() {
        // Given a machine that never started
        val GoMachine = DiscoveryStateMachine(GoGateway)

        // When a resolved event arrives out of order
        val GoResult = GoMachine.GoAccept(DiscoveryEvent.MDNS_RESOLVED)

        // Then it is refused with the typed illegal-transition failure
        assertThat(GoResult).isEqualTo(
            DiscoveryTransition.Rejected(
                DiscoveryState.IDLE,
                DiscoveryFailure.IllegalTransition(DiscoveryState.IDLE, DiscoveryEvent.MDNS_RESOLVED),
            ),
        )
    }

    @Test
    fun `select peer prefers mdns when both candidates exist`() {
        // Given both an mDNS hit and a configured gateway
        val GoMachine = DiscoveryStateMachine(GoGateway)

        // When a peer is selected
        val GoSelection = GoMachine.GoSelectPeer(GoMdns, GoGateway)

        // Then mDNS wins
        assertThat(GoSelection).isEqualTo(DiscoverySelection.Selected(GoMdns))
    }

    @Test
    fun `select peer falls back to the gateway on an mdns miss`() {
        // Given an mDNS miss and a configured gateway
        val GoMachine = DiscoveryStateMachine(GoGateway)

        // When a peer is selected
        val GoSelection = GoMachine.GoSelectPeer(mdns = null, gateway = GoGateway)

        // Then the gateway peer is selected (source = gateway)
        assertThat(GoSelection).isEqualTo(DiscoverySelection.Selected(GoGateway))
    }

    @Test
    fun `select peer reports NoPeer when neither candidate exists`() {
        // Given neither an mDNS hit nor a gateway
        val GoMachine = DiscoveryStateMachine(GoGatewayFallback = null)

        // When a peer is selected
        val GoSelection = GoMachine.GoSelectPeer(mdns = null, gateway = null)

        // Then there is nothing to connect to
        assertThat(GoSelection).isEqualTo(DiscoverySelection.NoPeer)
    }

    @Test
    fun `multicast lock is required below api 33 and on api 33 without T-ext 7`() {
        // Android 12L and below always need the lock
        assertThat(GoNeedsMulticastLock(29) { error("must not query SdkExtensions below 33") }).isTrue()
        assertThat(GoNeedsMulticastLock(32) { error("must not query SdkExtensions below 33") }).isTrue()

        // Android 13 needs it only while the Tiramisu extension is below 7
        assertThat(GoNeedsMulticastLock(33) { 0 }).isTrue()
        assertThat(GoNeedsMulticastLock(33) { 6 }).isTrue()
        assertThat(GoNeedsMulticastLock(33) { 7 }).isFalse()

        // Android 14+ never needs the app-held lock
        assertThat(GoNeedsMulticastLock(34) { error("must not query SdkExtensions above 33") }).isFalse()
        assertThat(GoNeedsMulticastLock(36) { error("must not query SdkExtensions above 33") }).isFalse()
    }

    @Test
    fun `multicast lock decision on api 33 reads the Tiramisu extension version`() {
        // Given a counter proving the extension is actually consulted on API 33 only
        var GoQueries = 0

        // When the decision is made on API 33
        GoNeedsMulticastLock(33) {
            GoQueries += 1
            7
        }

        // Then the extension version was read exactly once
        assertThat(GoQueries).isEqualTo(1)
    }

    @Test
    fun `service info callback is used from api 35`() {
        // The deprecated resolveService path is used up to and including API 34
        assertThat(GoUsesServiceInfoCallback(29)).isFalse()
        assertThat(GoUsesServiceInfoCallback(33)).isFalse()
        assertThat(GoUsesServiceInfoCallback(34)).isFalse()

        // registerServiceInfoCallback takes over from API 35
        assertThat(GoUsesServiceInfoCallback(35)).isTrue()
        assertThat(GoUsesServiceInfoCallback(36)).isTrue()
    }
}
