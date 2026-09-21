package com.glassstorm.phonemanager.adapter.repository.memory

import com.glassstorm.phonemanager.core.model.Device
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Behaviour of the in-memory fake adapter — mirrors the real repository port contract. */
class MemoryDeviceRepositoryTest {
    private fun pairedDevice(
        id: String,
        name: String = "glass",
        role: String = "GLASS",
        lastSeenMs: Long? = null,
    ): Device =
        Device(
            deviceId = id,
            deviceName = name,
            role = role,
            tokenHash = "hash-$id",
            pairedAtMs = 1_000L,
            lastSeenMs = lastSeenMs,
        )

    @Test
    fun `upsert then get returns the stored device`() {
        // Given an empty in-memory repository
        val repo: DeviceRepository = MemoryDeviceRepository()
        val device = pairedDevice(id = "d-1")

        // When a device is stored
        repo.upsert(device)

        // Then it is retrievable by id
        assertThat(repo.get("d-1")).isEqualTo(device)
    }

    @Test
    fun `upserting the same id overwrites without duplicating`() {
        // Given one stored device
        val repo = MemoryDeviceRepository()
        repo.upsert(pairedDevice(id = "d-1", name = "old"))

        // When the same id is upserted with new data
        repo.upsert(pairedDevice(id = "d-1", name = "new", role = "DAEMON"))

        // Then there is still exactly one row, updated
        assertThat(repo.list()).hasSize(1)
        assertThat(repo.get("d-1")?.deviceName).isEqualTo("new")
    }

    @Test
    fun `delete removes the device`() {
        // Given a stored device
        val repo = MemoryDeviceRepository()
        repo.upsert(pairedDevice(id = "d-1"))

        // When it is deleted
        repo.delete("d-1")

        // Then it is gone
        assertThat(repo.get("d-1")).isNull()
        assertThat(repo.list()).isEmpty()
    }

    @Test
    fun `get of an unknown id returns null`() {
        val repo = MemoryDeviceRepository()
        assertThat(repo.get("missing")).isNull()
    }

    @Test
    fun `get by token hash finds the matching device`() {
        // Given two stored devices with distinct token hashes
        val repo = MemoryDeviceRepository()
        repo.upsert(pairedDevice(id = "d-1"))
        repo.upsert(pairedDevice(id = "d-2", name = "daemon", role = "DAEMON"))

        // When looked up by the hash of the second
        // Then only that device is returned
        assertThat(repo.getByTokenHash("hash-d-2")?.deviceId).isEqualTo("d-2")
        assertThat(repo.getByTokenHash("hash-missing")).isNull()
    }

    @Test
    fun `touch updates last seen without changing other fields`() {
        // Given a stored device that has never been seen
        val repo = MemoryDeviceRepository()
        repo.upsert(pairedDevice(id = "d-1"))

        // When it is touched
        repo.touch("d-1", 9_999L)

        // Then only the last-seen instant moved
        val updated = repo.get("d-1")!!
        assertThat(updated.lastSeenMs).isEqualTo(9_999L)
        assertThat(updated.deviceName).isEqualTo("glass")
    }
}
