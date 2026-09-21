package com.glassstorm.phonemanager

import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.context.Context
import com.glassstorm.phonemanager.core.domain.context.fromContext
import com.glassstorm.phonemanager.core.domain.service.DeviceService
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.model.Device
import com.glassstorm.phonemanager.service.security.TokenVerifier
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Proves the `:app` composition root wires the vertical slice end-to-end:
 * domain port -> adapter fake -> service, all resolved through `fromContext`.
 *
 * The hub additions are asserted too: the gRPC services and the auth interceptor
 * resolve their collaborators EAGERLY when a server is built, so a Context that
 * forgot any of these would fail at `start`, not at first use.
 */
class AppCompositionTest {
    @Test
    fun `composition root registers all three layers into one Context`() {
        // Given the app composition root
        val ctx: Context = AppComposition.buildContext()

        // When each layer is resolved by its domain type
        val repo = fromContext<DeviceRepository>(ctx)
        val service = fromContext<DeviceService>(ctx)

        // Then all three registrations are present and usable
        assertThat(repo).isNotNull()
        assertThat(service).isNotNull()
        service.registerDevice(
            Device(
                deviceId = "d-1",
                deviceName = "glass",
                role = "GLASS",
                tokenHash = "hash-d-1",
                pairedAtMs = 1_000L,
                lastSeenMs = null,
            ),
        )
        assertThat(service.listDevices().map { it.deviceId }).containsExactly("d-1")
    }

    @Test
    fun `the service resolved from Context delegates to the registered adapter`() {
        // Given a wired Context
        val ctx = AppComposition.buildContext()

        // When the service registers through the port
        val service = fromContext<DeviceService>(ctx)
        service.registerDevice(
            Device(
                deviceId = "d-2",
                deviceName = "daemon",
                role = "DAEMON",
                tokenHash = "hash-d-2",
                pairedAtMs = 2_000L,
                lastSeenMs = null,
            ),
        )

        // Then the adapter bound under the port type sees the same write
        val repo = fromContext<DeviceRepository>(ctx)
        assertThat(repo.get("d-2")?.deviceName).isEqualTo("daemon")
    }

    @Test
    fun `the HubServer port resolves from the Context`() {
        // Given the app composition root
        val ctx = AppComposition.buildContext()

        // When the transport port is resolved by its domain type
        val hub = fromContext<HubServer>(ctx)

        // Then a real, stopped listener comes back (resolved without starting it)
        assertThat(hub).isNotNull()
        assertThat(hub.isRunning()).isFalse()
        assertThat(hub.boundPort()).isEqualTo(0)
    }

    @Test
    fun `the hub collaborator ports the grpc services resolve eagerly are registered`() {
        // Given the app composition root
        val ctx = AppComposition.buildContext()

        // When every collaborator the gRPC services and interceptor need is resolved
        // Then none throws MissingFromContextException — a missing one is a start crash
        assertThat(fromContext<PairingService>(ctx)).isNotNull()
        assertThat(fromContext<StreamService>(ctx)).isNotNull()
        assertThat(fromContext<TokenVerifier>(ctx)).isNotNull()
    }

    @Test
    fun `the pairing service and the token verifier are the same instance`() {
        // Given the app composition root
        val ctx = AppComposition.buildContext()

        // When both the service surface and the interceptor's verifier are resolved
        val pairing = fromContext<PairingService>(ctx)
        val verifier = fromContext<TokenVerifier>(ctx)

        // Then they are identical, so the interceptor can never verify against a
        // different pairing state than the one Pair mints tokens into.
        assertThat(verifier).isSameInstanceAs(pairing)
    }

    @Test
    fun `the app context is a process-wide singleton`() {
        // Given two resolutions of the app registry
        val first = AppComposition.appContext()
        val second = AppComposition.appContext()

        // Then it is one instance, so the service and the UI share one hub state
        assertThat(second).isSameInstanceAs(first)
    }
}
