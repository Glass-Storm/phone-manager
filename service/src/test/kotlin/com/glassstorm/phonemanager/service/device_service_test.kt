package com.glassstorm.phonemanager.service

import com.google.common.truth.Truth.assertThat
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContext
import com.glassstorm.phonemanager.domain.context.MissingFromContextException
import com.glassstorm.phonemanager.domain.context.Register
import com.glassstorm.phonemanager.domain.dto.Device
import com.glassstorm.phonemanager.domain.service.DeviceService
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Proves the service resolves its collaborator through the domain INTERFACE
 * from the Context registry (never a constructor-injected concrete adapter).
 */
class DeviceServiceTest {

    /** Test-local fake of the domain port — deliberately NOT the `:adapter` class. */
    private class GoFakeDeviceRepository : DeviceRepository {
        private val GoRows: MutableMap<String, Device> = mutableMapOf()
        override fun GoUpsert(device: Device) {
            GoRows[device.GoDeviceId] = device
        }

        override fun GoGet(deviceId: String): Device? = GoRows[deviceId]
        override fun GoList(): List<Device> = GoRows.values.sortedBy { it.GoDeviceId }
        override fun GoDelete(deviceId: String) {
            GoRows.remove(deviceId)
        }
    }

    @Test
    fun `service works with a fake adapter registered under the domain interface`() {
        // Given a Context holding a DeviceRepository under the domain port type
        val GoCtx = Context()
        Register<DeviceRepository>(GoCtx, GoFakeDeviceRepository())
        val GoService = DeviceServiceImpl(GoCtx)
        val GoDevice = Device(GoDeviceId = "d-1", GoDeviceName = "glass", GoRole = "GLASS")

        // When the service is used
        GoService.GoRegisterDevice(GoDevice)

        // Then it delegated to the registered interface implementation
        assertThat(GoService.GoListDevices()).containsExactly(GoDevice)
        assertThat(FromContext<DeviceRepository>(GoCtx).GoGet("d-1")).isEqualTo(GoDevice)
    }

    @Test
    fun `service resolves the interface type, so any implementation is interchangeable`() {
        // Given a Context holding only the interface binding
        val GoCtx = Context()
        Register<DeviceRepository>(GoCtx, GoFakeDeviceRepository())
        val GoServiceA = DeviceServiceImpl(GoCtx)
        val GoServiceB = DeviceServiceImpl(Context().also { Register<DeviceRepository>(it, GoFakeDeviceRepository()) })

        // When each service registers a distinct device
        GoServiceA.GoRegisterDevice(Device(GoDeviceId = "a", GoDeviceName = "A", GoRole = "GLASS"))
        GoServiceB.GoRegisterDevice(Device(GoDeviceId = "b", GoDeviceName = "B", GoRole = "DAEMON"))

        // Then their stores are independent, proving resolution is by interface binding
        assertThat(GoServiceA.GoListDevices().map { it.GoDeviceId }).containsExactly("a")
        assertThat(GoServiceB.GoListDevices().map { it.GoDeviceId }).containsExactly("b")
    }

    @Test
    fun `service throws when no collaborator is registered`() {
        // Given an empty Context
        val GoCtx = Context()
        val GoService = DeviceServiceImpl(GoCtx)

        // When/Then the missing binding surfaces as the typed absence error
        assertThrows(MissingFromContextException::class.java) {
            GoService.GoListDevices()
        }
    }

    @Test
    fun `registry can hold the service itself under its domain interface`() {
        // Given a wired Context (the composition-root pattern)
        val GoCtx = Context()
        Register<DeviceRepository>(GoCtx, GoFakeDeviceRepository())
        Register<DeviceService>(GoCtx, DeviceServiceImpl(GoCtx))

        // When the app resolves the service by interface
        val GoResolved = FromContext<DeviceService>(GoCtx)
        GoResolved.GoRegisterDevice(Device(GoDeviceId = "x", GoDeviceName = "X", GoRole = "GLASS"))

        // Then it is usable through the port
        assertThat(GoResolved.GoListDevices().map { it.GoDeviceId }).containsExactly("x")
    }
}
