package com.glassstorm.phonemanager.domain.service

import com.glassstorm.phonemanager.domain.dto.Device

/**
 * Device management use-cases.
 *
 * Port (interface) owned by `:domain`; implemented in `:service`, which
 * resolves its [DeviceRepository] collaborator through the Context registry.
 */
interface DeviceService {
    fun GoRegisterDevice(device: Device): Device

    fun GoListDevices(): List<Device>

    fun GoRemoveDevice(deviceId: String)
}
