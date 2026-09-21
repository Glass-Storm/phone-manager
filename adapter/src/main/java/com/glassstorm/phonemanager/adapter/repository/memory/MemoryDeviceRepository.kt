package com.glassstorm.phonemanager.adapter.repository.memory

import com.glassstorm.phonemanager.core.model.Device
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository

/**
 * In-memory [DeviceRepository] implementation.
 *
 * The reference fake used by the vertical sample slice: it satisfies the domain
 * port without any Android or SQLite dependency, so `:service` tests and the
 * composition root can be exercised on a plain JVM.
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
