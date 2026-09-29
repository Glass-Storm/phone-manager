package com.glassstorm.phonemanager.testing.testkit.repository

import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.model.Device

/**
 * In-memory [DeviceRepository] test double.
 *
 * It satisfies the domain port without any Android or SQLite dependency, so the
 * `:service`/`:transport:grpc` suites and the `:app` E2E harness can exercise the
 * graph on a plain JVM (or under Robolectric for `:app`).
 */
class MemoryDeviceRepository : DeviceRepository {
    private val rows: MutableMap<String, Device> = LinkedHashMap()

    override fun upsert(device: Device) {
        rows[device.deviceId] = device
    }

    override fun get(deviceId: String): Device? = rows[deviceId]

    override fun getByTokenHash(tokenHash: String): Device? = rows.values.firstOrNull { it.tokenHash == tokenHash }

    override fun list(): List<Device> = rows.values.toList()

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
