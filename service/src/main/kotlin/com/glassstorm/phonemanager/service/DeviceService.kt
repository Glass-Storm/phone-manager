package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContext
import com.glassstorm.phonemanager.domain.dto.Device
import com.glassstorm.phonemanager.domain.service.DeviceService

/**
 * Device use-case implementation.
 *
 * Resolves its [DeviceRepository] collaborator from the Context registry by the
 * domain INTERFACE type — never by a concrete adapter class. `:service` has no
 * build edge to `:adapter`, so the concrete implementation is unknowable here
 * by construction.
 */
class DeviceServiceImpl(
    private val ctx: Context,
) : DeviceService {
    private fun repo(): DeviceRepository = FromContext<DeviceRepository>(ctx)

    override fun registerDevice(device: Device): Device {
        repo().upsert(device)
        return device
    }

    override fun listDevices(): List<Device> = repo().list()

    override fun removeDevice(deviceId: String) {
        repo().delete(deviceId)
    }
}
