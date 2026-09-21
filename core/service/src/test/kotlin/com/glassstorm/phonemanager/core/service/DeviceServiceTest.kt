package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.domain.service.DeviceService
import com.glassstorm.phonemanager.core.model.Device
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Proves the service takes its collaborator as a CONSTRUCTOR dependency through
 * the domain INTERFACE — any implementation is interchangeable, and a failing one
 * surfaces its own failure rather than a lookup error.
 */
class DeviceServiceTest {
    /** Test-local fake of the domain port — deliberately NOT the `:adapter` class. */
    private class FakeDeviceRepository : DeviceRepository {
        private val rows: MutableMap<String, Device> = mutableMapOf()

        override fun upsert(device: Device) {
            rows[device.deviceId] = device
        }

        override fun get(deviceId: String): Device? = rows[deviceId]

        override fun getByTokenHash(tokenHash: String): Device? = rows.values.firstOrNull { it.tokenHash == tokenHash }

        override fun list(): List<Device> = rows.values.sortedBy { it.deviceId }

        override fun touch(
            deviceId: String,
            seenAtMs: Long,
        ) {
            val existing = rows[deviceId] ?: return
            rows[deviceId] = existing.copy(lastSeenMs = seenAtMs)
        }

        override fun delete(deviceId: String) {
            rows.remove(deviceId)
        }
    }

    @Test
    fun `service delegates to the repository injected through the domain interface`() {
        // Given a repository fake injected as the domain port
        val repository = FakeDeviceRepository()
        val service = DeviceServiceImpl(repository)
        val device =
            Device(
                deviceId = "d-1",
                deviceName = "glass",
                role = "GLASS",
                tokenHash = "hash-d-1",
                pairedAtMs = 1_000L,
                lastSeenMs = null,
            )

        // When the service is used
        service.registerDevice(device)

        // Then it delegated to the injected implementation
        assertThat(service.listDevices()).containsExactly(device)
        assertThat(repository.get("d-1")).isEqualTo(device)
    }

    @Test
    fun `any repository implementation is interchangeable`() {
        // Given two services over independent repositories
        val serviceA = DeviceServiceImpl(FakeDeviceRepository())
        val serviceB = DeviceServiceImpl(FakeDeviceRepository())

        // When each service registers a distinct device
        serviceA.registerDevice(
            Device(
                deviceId = "a",
                deviceName = "A",
                role = "GLASS",
                tokenHash = "hash-a",
                pairedAtMs = 1_000L,
                lastSeenMs = null,
            ),
        )
        serviceB.registerDevice(
            Device(
                deviceId = "b",
                deviceName = "B",
                role = "DAEMON",
                tokenHash = "hash-b",
                pairedAtMs = 2_000L,
                lastSeenMs = null,
            ),
        )

        // Then their stores are independent, proving construction over the interface
        assertThat(serviceA.listDevices().map { it.deviceId }).containsExactly("a")
        assertThat(serviceB.listDevices().map { it.deviceId }).containsExactly("b")
    }

    @Test
    fun `a failing repository propagates its failure rather than hiding it`() {
        // Given a repository whose read throws
        val failing =
            object : DeviceRepository {
                override fun upsert(device: Device) = Unit

                override fun get(deviceId: String): Device? = null

                override fun getByTokenHash(tokenHash: String): Device? = null

                override fun list(): List<Device> = throw IllegalStateException("device store unavailable")

                override fun touch(
                    deviceId: String,
                    seenAtMs: Long,
                ) = Unit

                override fun delete(deviceId: String) = Unit
            }
        val service = DeviceServiceImpl(failing)

        // When/Then the wrapped failure surfaces, never a silent empty list
        assertThrows(IllegalStateException::class.java) {
            service.listDevices()
        }
    }

    @Test
    fun `the service can be used through its domain interface`() {
        // Given a service constructed over the domain port
        val service: DeviceService = DeviceServiceImpl(FakeDeviceRepository())

        // When the app uses it by interface
        service.registerDevice(
            Device(
                deviceId = "x",
                deviceName = "X",
                role = "GLASS",
                tokenHash = "hash-x",
                pairedAtMs = 1_000L,
                lastSeenMs = null,
            ),
        )

        // Then it is usable through the port
        assertThat(service.listDevices().map { it.deviceId }).containsExactly("x")
    }
}
