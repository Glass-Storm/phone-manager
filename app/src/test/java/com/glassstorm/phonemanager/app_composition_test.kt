package com.glassstorm.phonemanager

import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContext
import com.glassstorm.phonemanager.domain.dto.Device
import com.glassstorm.phonemanager.domain.service.DeviceService
import com.glassstorm.phonemanager.domain.service.PairingService
import com.glassstorm.phonemanager.domain.service.StreamService
import com.glassstorm.phonemanager.service.security.TokenVerifier
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Proves the `:app` composition root wires the vertical slice end-to-end:
 * domain port -> adapter fake -> service, all resolved through `FromContext`.
 *
 * The hub additions are asserted too: the gRPC services and the auth interceptor
 * resolve their collaborators EAGERLY when a server is built, so a Context that
 * forgot any of these would fail at `GoStart`, not at first use.
 */
class AppCompositionTest {
    @Test
    fun `composition root registers all three layers into one Context`() {
        // Given the app composition root
        val GoCtx: Context = AppComposition.GoBuildContext()

        // When each layer is resolved by its domain type
        val GoRepo = FromContext<DeviceRepository>(GoCtx)
        val GoService = FromContext<DeviceService>(GoCtx)

        // Then all three registrations are present and usable
        assertThat(GoRepo).isNotNull()
        assertThat(GoService).isNotNull()
        GoService.GoRegisterDevice(
            Device(
                GoDeviceId = "d-1",
                GoDeviceName = "glass",
                GoRole = "GLASS",
                GoTokenHash = "hash-d-1",
                GoPairedAtMs = 1_000L,
                GoLastSeenMs = null,
            ),
        )
        assertThat(GoService.GoListDevices().map { it.GoDeviceId }).containsExactly("d-1")
    }

    @Test
    fun `the service resolved from Context delegates to the registered adapter`() {
        // Given a wired Context
        val GoCtx = AppComposition.GoBuildContext()

        // When the service registers through the port
        val GoService = FromContext<DeviceService>(GoCtx)
        GoService.GoRegisterDevice(
            Device(
                GoDeviceId = "d-2",
                GoDeviceName = "daemon",
                GoRole = "DAEMON",
                GoTokenHash = "hash-d-2",
                GoPairedAtMs = 2_000L,
                GoLastSeenMs = null,
            ),
        )

        // Then the adapter bound under the port type sees the same write
        val GoRepo = FromContext<DeviceRepository>(GoCtx)
        assertThat(GoRepo.GoGet("d-2")?.GoDeviceName).isEqualTo("daemon")
    }

    @Test
    fun `the HubServer port resolves from the Context`() {
        // Given the app composition root
        val GoCtx = AppComposition.GoBuildContext()

        // When the transport port is resolved by its domain type
        val GoHub = FromContext<HubServer>(GoCtx)

        // Then a real, stopped listener comes back (resolved without starting it)
        assertThat(GoHub).isNotNull()
        assertThat(GoHub.GoIsRunning()).isFalse()
        assertThat(GoHub.GoBoundPort()).isEqualTo(0)
    }

    @Test
    fun `the hub collaborator ports the grpc services resolve eagerly are registered`() {
        // Given the app composition root
        val GoCtx = AppComposition.GoBuildContext()

        // When every collaborator the gRPC services and interceptor need is resolved
        // Then none throws MissingFromContextException — a missing one is a GoStart crash
        assertThat(FromContext<PairingService>(GoCtx)).isNotNull()
        assertThat(FromContext<StreamService>(GoCtx)).isNotNull()
        assertThat(FromContext<TokenVerifier>(GoCtx)).isNotNull()
    }

    @Test
    fun `the pairing service and the token verifier are the same instance`() {
        // Given the app composition root
        val GoCtx = AppComposition.GoBuildContext()

        // When both the service surface and the interceptor's verifier are resolved
        val GoPairing = FromContext<PairingService>(GoCtx)
        val GoVerifier = FromContext<TokenVerifier>(GoCtx)

        // Then they are identical, so the interceptor can never verify against a
        // different pairing state than the one Pair mints tokens into.
        assertThat(GoVerifier).isSameInstanceAs(GoPairing)
    }

    @Test
    fun `the app context is a process-wide singleton`() {
        // Given two resolutions of the app registry
        val GoFirst = AppComposition.GoAppContext()
        val GoSecond = AppComposition.GoAppContext()

        // Then it is one instance, so the service and the UI share one hub state
        assertThat(GoSecond).isSameInstanceAs(GoFirst)
    }
}
