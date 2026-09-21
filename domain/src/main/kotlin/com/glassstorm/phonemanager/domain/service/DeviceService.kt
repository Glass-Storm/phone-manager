package com.glassstorm.phonemanager.domain.service

import com.glassstorm.phonemanager.core.model.Device

/**
 * Device management use-cases.
 *
 * Port (interface) owned by `:domain`; implemented in `:service`, which
 * resolves its [DeviceRepository] collaborator through the Context registry.
 */
interface DeviceService {
    fun registerDevice(device: Device): Device

    fun listDevices(): List<Device>

    fun removeDevice(deviceId: String)
}
