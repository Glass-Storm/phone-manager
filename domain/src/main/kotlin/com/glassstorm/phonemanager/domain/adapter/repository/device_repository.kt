package com.glassstorm.phonemanager.domain.adapter.repository

import com.glassstorm.phonemanager.domain.dto.Device

/**
 * Persistent store for paired devices.
 *
 * Port (interface) owned by `:domain`; implemented in `:adapter` (e.g. SQLite)
 * and resolved by `:service` through the Context registry.
 */
interface DeviceRepository {
    fun GoUpsert(device: Device)
    fun GoGet(deviceId: String): Device?
    fun GoGetByTokenHash(tokenHash: String): Device?
    fun GoList(): List<Device>
    fun GoTouch(deviceId: String, seenAtMs: Long)
    fun GoDelete(deviceId: String)
}
