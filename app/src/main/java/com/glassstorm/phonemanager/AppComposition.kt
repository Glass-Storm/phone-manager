package com.glassstorm.phonemanager

import com.glassstorm.phonemanager.adapter.repository.memory.MemoryDeviceRepository
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.Register
import com.glassstorm.phonemanager.domain.service.DeviceService
import com.glassstorm.phonemanager.service.DeviceServiceImpl

/**
 * Composition root. The ONLY place that knows every layer at once: it binds the
 * domain ports to concrete adapter/service implementations inside a [Context].
 *
 * This vertical slice is the template later todos follow:
 *   domain port  ->  `:adapter` implementation  ->  `:service` implementation,
 * all resolved elsewhere via `FromContext<Port>(ctx)`.
 */
object AppComposition {

    fun GoBuildContext(): Context {
        val GoCtx = Context()

        // Adapter layer: bind the concrete repository under the DOMAIN port type.
        Register<DeviceRepository>(GoCtx, MemoryDeviceRepository())

        // Service layer: the service resolves its collaborator from the Context.
        Register<DeviceService>(GoCtx, DeviceServiceImpl(GoCtx))

        return GoCtx
    }
}
