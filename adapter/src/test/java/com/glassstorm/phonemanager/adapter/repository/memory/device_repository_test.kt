package com.glassstorm.phonemanager.adapter.repository.memory

import com.google.common.truth.Truth.assertThat
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.dto.Device
import org.junit.Test

/** Behaviour of the in-memory fake adapter — mirrors the real repository port contract. */
class MemoryDeviceRepositoryTest {

    @Test
    fun `upsert then get returns the stored device`() {
        // Given an empty in-memory repository
        val GoRepo: DeviceRepository = MemoryDeviceRepository()
        val GoDevice = Device(GoDeviceId = "d-1", GoDeviceName = "glass", GoRole = "GLASS")

        // When a device is stored
        GoRepo.GoUpsert(GoDevice)

        // Then it is retrievable by id
        assertThat(GoRepo.GoGet("d-1")).isEqualTo(GoDevice)
    }

    @Test
    fun `upserting the same id overwrites without duplicating`() {
        // Given one stored device
        val GoRepo = MemoryDeviceRepository()
        GoRepo.GoUpsert(Device(GoDeviceId = "d-1", GoDeviceName = "old", GoRole = "GLASS"))

        // When the same id is upserted with new data
        GoRepo.GoUpsert(Device(GoDeviceId = "d-1", GoDeviceName = "new", GoRole = "DAEMON"))

        // Then there is still exactly one row, updated
        assertThat(GoRepo.GoList()).hasSize(1)
        assertThat(GoRepo.GoGet("d-1")?.GoDeviceName).isEqualTo("new")
    }

    @Test
    fun `delete removes the device`() {
        // Given a stored device
        val GoRepo = MemoryDeviceRepository()
        GoRepo.GoUpsert(Device(GoDeviceId = "d-1", GoDeviceName = "glass", GoRole = "GLASS"))

        // When it is deleted
        GoRepo.GoDelete("d-1")

        // Then it is gone
        assertThat(GoRepo.GoGet("d-1")).isNull()
        assertThat(GoRepo.GoList()).isEmpty()
    }

    @Test
    fun `get of an unknown id returns null`() {
        val GoRepo = MemoryDeviceRepository()
        assertThat(GoRepo.GoGet("missing")).isNull()
    }
}
