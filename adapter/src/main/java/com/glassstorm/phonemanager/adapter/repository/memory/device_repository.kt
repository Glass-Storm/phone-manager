package com.glassstorm.phonemanager.adapter.repository.memory

import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.dto.Device

/**
 * In-memory [DeviceRepository] implementation.
 *
 * The reference fake used by the vertical sample slice: it satisfies the domain
 * port without any Android or SQLite dependency, so `:service` tests and the
 * composition root can be exercised on a plain JVM.
 */
class MemoryDeviceRepository : DeviceRepository {

    private val GoRows: MutableMap<String, Device> = LinkedHashMap()

    override fun GoUpsert(device: Device) {
        GoRows[device.GoDeviceId] = device
    }

    override fun GoGet(deviceId: String): Device? = GoRows[deviceId]

    override fun GoGetByTokenHash(tokenHash: String): Device? =
        GoRows.values.firstOrNull { it.GoTokenHash == tokenHash }

    override fun GoList(): List<Device> = GoRows.values.toList()

    override fun GoTouch(deviceId: String, seenAtMs: Long) {
        val GoExisting = GoRows[deviceId] ?: return
        GoRows[deviceId] = GoExisting.copy(GoLastSeenMs = seenAtMs)
    }

    override fun GoDelete(deviceId: String) {
        GoRows.remove(deviceId)
    }
}
