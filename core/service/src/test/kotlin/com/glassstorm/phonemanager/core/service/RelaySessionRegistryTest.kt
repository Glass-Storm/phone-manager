package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.model.LivePeer
import com.glassstorm.phonemanager.core.model.PeerRole
import com.glassstorm.phonemanager.core.model.RelayOutput
import com.glassstorm.phonemanager.core.model.RelaySession
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import org.junit.Test

/** Direct proof for [RelaySessionRegistry]: register/find/remove/size and the role index. */
class RelaySessionRegistryTest {
    private fun state(deviceId: String): SessionState =
        SessionState(
            relay = RelaySession(sessionId = "s-$deviceId", deviceId = deviceId),
            queue = RelayQueue(audioCapacity = 1, videoCapacity = 1, imageCapacity = 1),
            results = Channel<RelayOutput>(Channel.UNLIMITED),
            tts = Channel<String>(capacity = 1),
            imageDrained = CompletableDeferred(),
            pump = Job(),
        )

    @Test
    fun `a registered session is found by id`() {
        // Given a registered session
        val registry = RelaySessionRegistry()
        val session = state("a")
        registry.register("s-a", session)

        // Then it is found under that id
        assertThat(registry.find("s-a")).isSameInstanceAs(session)
    }

    @Test
    fun `an unknown session is not found`() {
        // Given a registry with one session
        val registry = RelaySessionRegistry()
        registry.register("s-a", state("a"))

        // Then an unknown id resolves to null
        assertThat(registry.find("s-missing")).isNull()
    }

    @Test
    fun `remove returns the session and drops it from the registry`() {
        // Given a registered session
        val registry = RelaySessionRegistry()
        val session = state("a")
        registry.register("s-a", session)

        // When it is removed
        val removed = registry.remove("s-a")

        // Then the same instance came back and it is no longer tracked
        assertThat(removed).isSameInstanceAs(session)
        assertThat(registry.find("s-a")).isNull()
    }

    @Test
    fun `removing an unknown session returns null`() {
        // Given an empty registry
        val registry = RelaySessionRegistry()

        // Then removing an unknown id is a null-returning no-op
        assertThat(registry.remove("s-missing")).isNull()
        assertThat(registry.size()).isEqualTo(0)
    }

    @Test
    fun `size reflects the number of live sessions`() {
        // Given an empty registry
        val registry = RelaySessionRegistry()
        assertThat(registry.size()).isEqualTo(0)

        // When sessions are registered
        registry.register("s-a", state("a"))
        registry.register("s-b", state("b"))

        // Then size tracks them
        assertThat(registry.size()).isEqualTo(2)

        // And shrinks again after a removal
        registry.remove("s-a")
        assertThat(registry.size()).isEqualTo(1)
    }

    @Test
    fun `a bound role resolves to its session and an unbound role to null`() {
        // Given a registered session
        val registry = RelaySessionRegistry()
        registry.register("s-necklace", state("necklace"))

        // Then an unbound role resolves to nothing
        assertThat(registry.sessionFor(PeerRole.GLASS)).isNull()

        // When the session binds the daemon role
        registry.bindRole("s-necklace", PeerRole.DAEMON)

        // Then the role index resolves it, and the other role stays unbound
        assertThat(registry.sessionFor(PeerRole.DAEMON)).isEqualTo("s-necklace")
        assertThat(registry.sessionFor(PeerRole.GLASS)).isNull()
    }

    @Test
    fun `an unspecified role is never bound`() {
        // Given a registry
        val registry = RelaySessionRegistry()
        registry.register("s-a", state("a"))

        // When an unspecified role is bound
        registry.bindRole("s-a", PeerRole.UNSPECIFIED)

        // Then nothing resolves for it
        assertThat(registry.sessionFor(PeerRole.UNSPECIFIED)).isNull()
    }

    @Test
    fun `live peers resolve their bound role and default to unspecified`() {
        // Given two sessions, only one of which bound a role
        val registry = RelaySessionRegistry()
        registry.register("s-glasses", state("glasses"))
        registry.register("s-necklace", state("necklace"))
        registry.bindRole("s-glasses", PeerRole.GLASS)

        // Then the bound session carries its role and the other stays unspecified
        assertThat(registry.livePeers())
            .containsExactly(
                LivePeer(deviceId = "glasses", role = PeerRole.GLASS),
                LivePeer(deviceId = "necklace", role = PeerRole.UNSPECIFIED),
            )
    }

    @Test
    fun `a removed session disappears from the live peers`() {
        // Given one registered session
        val registry = RelaySessionRegistry()
        registry.register("s-glasses", state("glasses"))

        // When it is removed
        registry.remove("s-glasses")

        // Then no live peer is reported
        assertThat(registry.livePeers()).isEmpty()
    }

    @Test
    fun `removing a session drops its role binding`() {
        // Given a session bound to a role
        val registry = RelaySessionRegistry()
        registry.register("s-glasses", state("glasses"))
        registry.bindRole("s-glasses", PeerRole.GLASS)
        assertThat(registry.sessionFor(PeerRole.GLASS)).isEqualTo("s-glasses")

        // When it is removed
        registry.remove("s-glasses")

        // Then the role index no longer resolves a dead session
        assertThat(registry.sessionFor(PeerRole.GLASS)).isNull()
    }

    @Test
    fun `closing the newer of two glass sessions rebinds the still live one`() {
        // Given two live GLASS sessions, the second bound last
        val registry = RelaySessionRegistry()
        registry.register("s-glasses-1", state("glasses-1"))
        registry.register("s-glasses-2", state("glasses-2"))
        registry.bindRole("s-glasses-1", PeerRole.GLASS)
        registry.bindRole("s-glasses-2", PeerRole.GLASS)
        assertThat(registry.sessionFor(PeerRole.GLASS)).isEqualTo("s-glasses-2")

        // When the newer session closes
        registry.remove("s-glasses-2")

        // Then the still-live older session holds the role again, so gestures and
        // TTS keep routing to it instead of being dropped until it reconnects
        assertThat(registry.sessionFor(PeerRole.GLASS)).isEqualTo("s-glasses-1")
        assertThat(registry.livePeers())
            .containsExactly(LivePeer(deviceId = "glasses-1", role = PeerRole.GLASS))
    }

    @Test
    fun `rebinding the same session moves it to the top instead of duplicating`() {
        // Given a GLASS session that re-sends its stream_open frame
        val registry = RelaySessionRegistry()
        registry.register("s-glasses", state("glasses"))
        registry.bindRole("s-glasses", PeerRole.GLASS)
        registry.bindRole("s-glasses", PeerRole.GLASS)

        // When it closes
        registry.remove("s-glasses")

        // Then the single history entry is gone and the role is cleared
        assertThat(registry.sessionFor(PeerRole.GLASS)).isNull()
    }
}
