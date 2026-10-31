package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.domain.service.DeviceService
import com.glassstorm.phonemanager.core.domain.service.StreamService
import dagger.Binds
import dagger.Module
import javax.inject.Singleton

/**
 * Dagger bindings for the use-case layer.
 *
 * Each port in `:core:domain` is bound to its implementation in THIS module by
 * [Binds], never by a `@Provides` returning a concrete class: the domain ports are
 * the collaboration surface, and a binding through the interface keeps
 * `:core:service`'s dependency on `:core:domain` the only one it needs.
 *
 * Pairing, PIN and bearer-token bindings were removed with the pairing runtime;
 * the `.proto` keeps `PairingService` for wire compatibility but the hub no longer
 * serves or verifies it.
 *
 * The bindings are `@Singleton` because every port here is process-wide state:
 * device rows and relay sessions.
 */
@Module
abstract class ServiceModule {
    @Binds
    @Singleton
    abstract fun bindDeviceService(impl: DeviceServiceImpl): DeviceService

    @Binds
    @Singleton
    abstract fun bindStreamService(impl: StreamServiceImpl): StreamService
}
