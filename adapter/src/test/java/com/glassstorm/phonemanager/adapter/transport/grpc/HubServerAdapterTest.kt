package com.glassstorm.phonemanager.adapter.transport.grpc

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Robolectric tests for the real Android hub transport adapter.
 *
 * There is no emulator on this host (issues.md R2), so these tests are the only
 * place the adapter's lifecycle is exercised. They bind a REAL netty-shaded NIO
 * socket on `127.0.0.1` — the same transport path the T6 spike proved GO — and
 * assert the port is actually released on stop, which is what makes a subsequent
 * production bind on the fixed hub port possible.
 *
 * Pinned to API 29 (the app's targetSdk) because that is the platform the app ships to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HubServerAdapterTest {
    private fun GoAdapter(): HubServerAdapter = HubServerAdapter(GoHubContext())

    @Test
    fun `default adapter binds the IPv4 loopback and never the wildcard`() {
        // Given the default adapter used by tests
        val GoHub = GoAdapter()

        // Then it binds the IPv4 loopback literal, not `0.0.0.0` and not a hostname
        assertThat(GoHub.GoBindAddress()).isEqualTo("127.0.0.1")
    }

    @Test
    fun `start on an ephemeral port reports running and the real bound port`() {
        // Given a stopped adapter on the IPv4 loopback
        val GoHub = GoAdapter()

        try {
            // When it starts on port 0 (ephemeral)
            GoHub.GoStart(0)

            // Then it is running and reports the ACTUAL assigned port, not 0
            assertThat(GoHub.GoIsRunning()).isTrue()
            assertWithMessage("GoBoundPort must report the assigned port after GoStart(0)")
                .that(GoHub.GoBoundPort())
                .isGreaterThan(0)
        } finally {
            GoHub.GoStop()
        }
    }

    @Test
    fun `stop releases the port and stopping twice is a no-op`() {
        // Given a running adapter
        val GoHub = GoAdapter()
        GoHub.GoStart(0)
        val GoPort = GoHub.GoBoundPort()
        assertThat(GoPort).isGreaterThan(0)

        // When it is stopped
        val GoStopStartNs = System.nanoTime()
        GoHub.GoStop()
        val GoStopMs = (System.nanoTime() - GoStopStartNs) / 1_000_000
        println("[QA] hung_commands: GoStop() returned in ${GoStopMs}ms (bounded drain, no hang)")

        // Then the listener is down, reports no port...
        assertThat(GoHub.GoIsRunning()).isFalse()
        assertThat(GoHub.GoBoundPort()).isEqualTo(0)

        // ...and the OS port is genuinely RELEASED: another socket can take it.
        // This is the assertion that a leaked/cached listener would fail.
        ServerSocket().use { GoProbe ->
            GoProbe.reuseAddress = false
            GoProbe.bind(InetSocketAddress("127.0.0.1", GoPort))
            assertThat(GoProbe.localPort).isEqualTo(GoPort)
        }

        // And a second stop is a no-op that does not throw (bounded drain, no hang)
        GoHub.GoStop()
        assertThat(GoHub.GoIsRunning()).isFalse()
    }

    @Test
    fun `start is idempotent while already running`() {
        // Given a running adapter on an ephemeral port
        val GoHub = GoAdapter()
        GoHub.GoStart(0)
        val GoPort = GoHub.GoBoundPort()

        try {
            // When start is called AGAIN with a different port
            GoHub.GoStart(0)

            // Then the original listener is untouched (start is idempotent)
            assertThat(GoHub.GoBoundPort()).isEqualTo(GoPort)
            assertThat(GoHub.GoIsRunning()).isTrue()
        } finally {
            GoHub.GoStop()
        }
    }

    @Test
    fun `starting on an already bound port fails cleanly and leaves the adapter stopped`() {
        // Given a port the OS has already taken
        ServerSocket().use { GoSquatter ->
            GoSquatter.reuseAddress = false
            GoSquatter.bind(InetSocketAddress("127.0.0.1", 0))
            val GoTaken = GoSquatter.localPort
            val GoHub = GoAdapter()

            // When the adapter tries to bind the same port
            var GoThrown: Throwable? = null
            try {
                GoHub.GoStart(GoTaken)
            } catch (GoFailure: Throwable) {
                GoThrown = GoFailure
            }

            // Then it fails cleanly rather than reporting a phantom listener
            println(
                "[QA] malformed_input: binding taken port $GoTaken failed with " +
                    "${GoThrown?.javaClass?.name}: ${GoThrown?.message}",
            )
            assertWithMessage("binding an in-use port must not silently succeed")
                .that(GoThrown)
                .isNotNull()
            assertThat(GoHub.GoIsRunning()).isFalse()
            assertThat(GoHub.GoBoundPort()).isEqualTo(0)

            // And the adapter can still start normally afterwards (no poisoned state)
            GoHub.GoStart(0)
            assertThat(GoHub.GoIsRunning()).isTrue()
            GoHub.GoStop()
        }
    }

    @Test
    fun `repeated start stop cycles never collide on the ephemeral port`() {
        // Given one adapter reused across cycles (flaky_tests probe: run twice+)
        val GoHub = GoAdapter()

        // When five start/stop cycles run back to back
        repeat(5) {
            GoHub.GoStart(0)
            val GoPort = GoHub.GoBoundPort()
            println("[QA] flaky_tests: cycle #$it bound ephemeral port $GoPort")
            assertThat(GoPort).isGreaterThan(0)
            GoHub.GoStop()
            assertThat(GoHub.GoIsRunning()).isFalse()
        }

        // Then the adapter is cleanly stopped and reusable
        GoHub.GoStart(0)
        assertThat(GoHub.GoIsRunning()).isTrue()
        GoHub.GoStop()
        assertThat(GoHub.GoBoundPort()).isEqualTo(0)
    }
}
