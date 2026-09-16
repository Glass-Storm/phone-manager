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
    private val GoCtx: Context,
) : DeviceService {
    private fun GoRepo(): DeviceRepository = FromContext<DeviceRepository>(GoCtx)

    override fun GoRegisterDevice(device: Device): Device {
        GoRepo().GoUpsert(device)
        return device
    }

    override fun GoListDevices(): List<Device> = GoRepo().GoList()

    override fun GoRemoveDevice(deviceId: String) {
        GoRepo().GoDelete(deviceId)
    }
}
