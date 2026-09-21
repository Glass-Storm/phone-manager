package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.core.model.Device
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.MissingFromContextException
import com.glassstorm.phonemanager.domain.context.fromContext
import com.glassstorm.phonemanager.domain.context.register
import com.glassstorm.phonemanager.domain.service.DeviceService
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Proves the service resolves its collaborator through the domain INTERFACE
 * from the Context registry (never a constructor-injected concrete adapter).
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
    fun `service works with a fake adapter registered under the domain interface`() {
        // Given a Context holding a DeviceRepository under the domain port type
        val ctx = Context()
        register<DeviceRepository>(ctx, FakeDeviceRepository())
        val service = DeviceServiceImpl(ctx)
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

        // Then it delegated to the registered interface implementation
        assertThat(service.listDevices()).containsExactly(device)
        assertThat(fromContext<DeviceRepository>(ctx).get("d-1")).isEqualTo(device)
    }

    @Test
    fun `service resolves the interface type, so any implementation is interchangeable`() {
        // Given a Context holding only the interface binding
        val ctx = Context()
        register<DeviceRepository>(ctx, FakeDeviceRepository())
        val serviceA = DeviceServiceImpl(ctx)
        val serviceB = DeviceServiceImpl(Context().also { register<DeviceRepository>(it, FakeDeviceRepository()) })

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

        // Then their stores are independent, proving resolution is by interface binding
        assertThat(serviceA.listDevices().map { it.deviceId }).containsExactly("a")
        assertThat(serviceB.listDevices().map { it.deviceId }).containsExactly("b")
    }

    @Test
    fun `service throws when no collaborator is registered`() {
        // Given an empty Context
        val ctx = Context()
        val service = DeviceServiceImpl(ctx)

        // When/Then the missing binding surfaces as the typed absence error
        assertThrows(MissingFromContextException::class.java) {
            service.listDevices()
        }
    }

    @Test
    fun `registry can hold the service itself under its domain interface`() {
        // Given a wired Context (the composition-root pattern)
        val ctx = Context()
        register<DeviceRepository>(ctx, FakeDeviceRepository())
        register<DeviceService>(ctx, DeviceServiceImpl(ctx))

        // When the app resolves the service by interface
        val resolved = fromContext<DeviceService>(ctx)
        resolved.registerDevice(
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
        assertThat(resolved.listDevices().map { it.deviceId }).containsExactly("x")
    }
}
