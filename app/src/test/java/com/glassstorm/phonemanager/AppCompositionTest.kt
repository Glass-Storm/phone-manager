package com.glassstorm.phonemanager

import com.google.common.truth.Truth.assertThat
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContext
import com.glassstorm.phonemanager.domain.dto.Device
import com.glassstorm.phonemanager.domain.service.DeviceService
import org.junit.Test

/**
 * Proves the `:app` composition root wires the vertical slice end-to-end:
 * domain port -> adapter fake -> service, all resolved through `FromContext`.
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
            )
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
            )
        )

        // Then the adapter bound under the port type sees the same write
        val GoRepo = FromContext<DeviceRepository>(GoCtx)
        assertThat(GoRepo.GoGet("d-2")?.GoDeviceName).isEqualTo("daemon")
    }
}
