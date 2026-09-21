package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.domain.service.DeviceService
import com.glassstorm.phonemanager.core.model.Device
import javax.inject.Inject

/**
 * Device use-case implementation.
 *
 * The [DeviceRepository] collaborator is a CONSTRUCTOR dependency. `:service` has
 * no build edge to `:adapter`, so the concrete implementation is unknowable here
 * by construction — Dagger binds it at the composition root.
 */
class DeviceServiceImpl
    @Inject
    constructor(
        private val repository: DeviceRepository,
    ) : DeviceService {
        override fun registerDevice(device: Device): Device {
            repository.upsert(device)
            return device
        }

        override fun listDevices(): List<Device> = repository.list()

        override fun removeDevice(deviceId: String) {
            repository.delete(deviceId)
        }
    }
