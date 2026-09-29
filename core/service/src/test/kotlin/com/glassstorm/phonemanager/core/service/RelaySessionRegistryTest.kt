package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.model.RelayResult
import com.glassstorm.phonemanager.core.model.RelaySession
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import org.junit.Test

/** Direct proof for [RelaySessionRegistry]: register/find/remove/size. */
class RelaySessionRegistryTest {
    private fun state(deviceId: String): SessionState =
        SessionState(
            relay = RelaySession(sessionId = "s-$deviceId", deviceId = deviceId),
            queue = RelayQueue(audioCapacity = 1, videoCapacity = 1),
            results = Channel<RelayResult>(Channel.UNLIMITED),
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
}
