package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.domain.context.Context
import com.glassstorm.phonemanager.core.domain.context.fromContext
import com.glassstorm.phonemanager.core.domain.service.DeviceService
import com.glassstorm.phonemanager.core.model.Device
import javax.inject.Inject

/**
 * Device use-case implementation.
 *
 * The [DeviceRepository] collaborator is a CONSTRUCTOR dependency. `:service` has
 * no build edge to `:adapter`, so the concrete implementation is unknowable here
 * by construction — Dagger binds it at the composition root.
 *
 * ## Constructor shape (transitional, T15)
 *
 * The collaborator is held as a provider so both construction paths stay
 * behaviourally identical while the `Context` registry still exists:
 *
 *  * the [Inject] constructor (Dagger) captures the already-bound instance;
 *  * the `Context` constructor performs the per-call lookup the previous
 *    implementation did, so a missing binding still surfaces on FIRST USE
 *    rather than at construction.
 *
 * T16 deletes the registry constructor and collapses this to a plain property.
 */
class DeviceServiceImpl private constructor(
    private val repositoryProvider: () -> DeviceRepository,
) : DeviceService {
    @Inject
    constructor(repository: DeviceRepository) : this({ repository })

    /** Registry-compat constructor; T16 removes it with the registry. */
    constructor(ctx: Context) : this({ fromContext(ctx) })

    override fun registerDevice(device: Device): Device {
        repositoryProvider().upsert(device)
        return device
    }

    override fun listDevices(): List<Device> = repositoryProvider().list()

    override fun removeDevice(deviceId: String) {
        repositoryProvider().delete(deviceId)
    }
}
