package com.glassstorm.phonemanager.transport.grpc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Tests for the real hub transport adapter.
 *
 * These bind a REAL netty-shaded NIO socket on `127.0.0.1` — the same transport
 * path the T6 spike proved GO — and assert the port is actually released on stop,
 * which is what makes a subsequent production bind on the fixed hub port possible.
 *
 * The adapter is pure JVM: it takes a `ServerBuilder` factory and never touches an
 * Android API, so these run on the plain JVM with no Robolectric.
 */
class HubServerAdapterTest {
    private fun adapter(): HubServerAdapter = HubServerAdapter(hubContext())

    @Test
    fun `default adapter binds the IPv4 loopback and never the wildcard`() {
        // Given the default adapter used by tests
        val hub = adapter()

        // Then it binds the IPv4 loopback literal, not `0.0.0.0` and not a hostname
        assertThat(hub.bindAddress()).isEqualTo("127.0.0.1")
    }

    @Test
    fun `start on an ephemeral port reports running and the real bound port`() {
        // Given a stopped adapter on the IPv4 loopback
        val hub = adapter()

        try {
            // When it starts on port 0 (ephemeral)
            hub.start(0)

            // Then it is running and reports the ACTUAL assigned port, not 0
            assertThat(hub.isRunning()).isTrue()
            assertWithMessage("boundPort must report the assigned port after start(0)")
                .that(hub.boundPort())
                .isGreaterThan(0)
        } finally {
            hub.stop()
        }
    }

    @Test
    fun `stop releases the port and stopping twice is a no-op`() {
        // Given a running adapter
        val hub = adapter()
        hub.start(0)
        val port = hub.boundPort()
        assertThat(port).isGreaterThan(0)

        // When it is stopped
        val stopStartNs = System.nanoTime()
        hub.stop()
        val stopMs = (System.nanoTime() - stopStartNs) / 1_000_000
        println("[QA] hung_commands: stop() returned in ${stopMs}ms (bounded drain, no hang)")

        // Then the listener is down, reports no port...
        assertThat(hub.isRunning()).isFalse()
        assertThat(hub.boundPort()).isEqualTo(0)

        // ...and the OS port is genuinely RELEASED: another socket can take it.
        // This is the assertion that a leaked/cached listener would fail.
        ServerSocket().use { probe ->
            probe.reuseAddress = false
            probe.bind(InetSocketAddress("127.0.0.1", port))
            assertThat(probe.localPort).isEqualTo(port)
        }

        // And a second stop is a no-op that does not throw (bounded drain, no hang)
        hub.stop()
        assertThat(hub.isRunning()).isFalse()
    }

    @Test
    fun `start is idempotent while already running`() {
        // Given a running adapter on an ephemeral port
        val hub = adapter()
        hub.start(0)
        val port = hub.boundPort()

        try {
            // When start is called AGAIN with a different port
            hub.start(0)

            // Then the original listener is untouched (start is idempotent)
            assertThat(hub.boundPort()).isEqualTo(port)
            assertThat(hub.isRunning()).isTrue()
        } finally {
            hub.stop()
        }
    }

    @Test
    fun `starting on an already bound port fails cleanly and leaves the adapter stopped`() {
        // Given a port the OS has already taken
        ServerSocket().use { squatter ->
            squatter.reuseAddress = false
            squatter.bind(InetSocketAddress("127.0.0.1", 0))
            val taken = squatter.localPort
            val hub = adapter()

            // When the adapter tries to bind the same port
            var thrown: Throwable? = null
            try {
                hub.start(taken)
            } catch (failure: Throwable) {
                thrown = failure
            }

            // Then it fails cleanly rather than reporting a phantom listener
            println(
                "[QA] malformed_input: binding taken port $taken failed with " +
                    "${thrown?.javaClass?.name}: ${thrown?.message}",
            )
            assertWithMessage("binding an in-use port must not silently succeed")
                .that(thrown)
                .isNotNull()
            assertThat(hub.isRunning()).isFalse()
            assertThat(hub.boundPort()).isEqualTo(0)

            // And the adapter can still start normally afterwards (no poisoned state)
            hub.start(0)
            assertThat(hub.isRunning()).isTrue()
            hub.stop()
        }
    }

    @Test
    fun `repeated start stop cycles never collide on the ephemeral port`() {
        // Given one adapter reused across cycles (flaky_tests probe: run twice+)
        val hub = adapter()

        // When five start/stop cycles run back to back
        repeat(5) {
            hub.start(0)
            val port = hub.boundPort()
            println("[QA] flaky_tests: cycle #$it bound ephemeral port $port")
            assertThat(port).isGreaterThan(0)
            hub.stop()
            assertThat(hub.isRunning()).isFalse()
        }

        // Then the adapter is cleanly stopped and reusable
        hub.start(0)
        assertThat(hub.isRunning()).isTrue()
        hub.stop()
        assertThat(hub.boundPort()).isEqualTo(0)
    }
}
