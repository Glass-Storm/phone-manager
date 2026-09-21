package com.glassstorm.phonemanager.domain.adapter.repository

import com.glassstorm.phonemanager.core.model.Device

/**
 * Persistent store for paired devices.
 *
 * Port (interface) owned by `:domain`; implemented in `:adapter` (e.g. SQLite)
 * and resolved by `:service` through the Context registry.
 */
interface DeviceRepository {
    fun upsert(device: Device)

    fun get(deviceId: String): Device?

    fun getByTokenHash(tokenHash: String): Device?

    fun list(): List<Device>

    fun touch(
        deviceId: String,
        seenAtMs: Long,
    )

    fun delete(deviceId: String)
}
