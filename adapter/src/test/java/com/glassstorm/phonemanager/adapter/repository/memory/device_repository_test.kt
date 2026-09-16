package com.glassstorm.phonemanager.adapter.repository.memory

import com.google.common.truth.Truth.assertThat
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.dto.Device
import org.junit.Test

/** Behaviour of the in-memory fake adapter — mirrors the real repository port contract. */
class MemoryDeviceRepositoryTest {

    private fun GoPairedDevice(
        id: String,
        name: String = "glass",
        role: String = "GLASS",
        lastSeenMs: Long? = null,
    ): Device = Device(
        GoDeviceId = id,
        GoDeviceName = name,
        GoRole = role,
        GoTokenHash = "hash-$id",
        GoPairedAtMs = 1_000L,
        GoLastSeenMs = lastSeenMs,
    )

    @Test
    fun `upsert then get returns the stored device`() {
        // Given an empty in-memory repository
        val GoRepo: DeviceRepository = MemoryDeviceRepository()
        val GoDevice = GoPairedDevice(id = "d-1")

        // When a device is stored
        GoRepo.GoUpsert(GoDevice)

        // Then it is retrievable by id
        assertThat(GoRepo.GoGet("d-1")).isEqualTo(GoDevice)
    }

    @Test
    fun `upserting the same id overwrites without duplicating`() {
        // Given one stored device
        val GoRepo = MemoryDeviceRepository()
        GoRepo.GoUpsert(GoPairedDevice(id = "d-1", name = "old"))

        // When the same id is upserted with new data
        GoRepo.GoUpsert(GoPairedDevice(id = "d-1", name = "new", role = "DAEMON"))

        // Then there is still exactly one row, updated
        assertThat(GoRepo.GoList()).hasSize(1)
        assertThat(GoRepo.GoGet("d-1")?.GoDeviceName).isEqualTo("new")
    }

    @Test
    fun `delete removes the device`() {
        // Given a stored device
        val GoRepo = MemoryDeviceRepository()
        GoRepo.GoUpsert(GoPairedDevice(id = "d-1"))

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

    @Test
    fun `get by token hash finds the matching device`() {
        // Given two stored devices with distinct token hashes
        val GoRepo = MemoryDeviceRepository()
        GoRepo.GoUpsert(GoPairedDevice(id = "d-1"))
        GoRepo.GoUpsert(GoPairedDevice(id = "d-2", name = "daemon", role = "DAEMON"))

        // When looked up by the hash of the second
        // Then only that device is returned
        assertThat(GoRepo.GoGetByTokenHash("hash-d-2")?.GoDeviceId).isEqualTo("d-2")
        assertThat(GoRepo.GoGetByTokenHash("hash-missing")).isNull()
    }

    @Test
    fun `touch updates last seen without changing other fields`() {
        // Given a stored device that has never been seen
        val GoRepo = MemoryDeviceRepository()
        GoRepo.GoUpsert(GoPairedDevice(id = "d-1"))

        // When it is touched
        GoRepo.GoTouch("d-1", 9_999L)

        // Then only the last-seen instant moved
        val GoUpdated = GoRepo.GoGet("d-1")!!
        assertThat(GoUpdated.GoLastSeenMs).isEqualTo(9_999L)
        assertThat(GoUpdated.GoDeviceName).isEqualTo("glass")
    }
}
