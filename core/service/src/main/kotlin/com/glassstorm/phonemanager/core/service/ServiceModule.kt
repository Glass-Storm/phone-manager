package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.domain.security.TokenVerifier
import com.glassstorm.phonemanager.core.domain.service.DeviceService
import com.glassstorm.phonemanager.core.domain.service.PairingService
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
 * ## The single-instance invariant
 *
 * [PairingServiceImpl] implements BOTH [PairingService] and [TokenVerifier]. Two
 * separate instances would let the gRPC Pairing service mint tokens into one
 * authority while the `AuthInterceptor` verified against another — they could
 * disagree about pairing state. [bindPairingService] and [bindTokenVerifier]
 * therefore both delegate to the SAME `@Singleton` `PairingServiceImpl`, which is
 * what makes the two ports return one object. The registry expressed the same
 * invariant by registering one instance twice.
 *
 * The bindings are `@Singleton` because every port here is process-wide state:
 * pairing windows, relay sessions, and the repository view behind them.
 */
@Module
abstract class ServiceModule {
    @Binds
    @Singleton
    abstract fun bindDeviceService(impl: DeviceServiceImpl): DeviceService

    @Binds
    @Singleton
    abstract fun bindPairingService(impl: PairingServiceImpl): PairingService

    @Binds
    @Singleton
    abstract fun bindTokenVerifier(impl: PairingServiceImpl): TokenVerifier

    @Binds
    @Singleton
    abstract fun bindStreamService(impl: StreamServiceImpl): StreamService
}
