package com.glassstorm.phonemanager.di

import android.app.Application
import com.glassstorm.phonemanager.adapter.android.di.AndroidAdapterModule
import com.glassstorm.phonemanager.adapter.android.di.ApplicationContext
import com.glassstorm.phonemanager.adapter.jvm.di.JvmAdapterModule
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.security.TokenVerifier
import com.glassstorm.phonemanager.core.domain.service.DeviceService
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.service.ServiceModule
import com.glassstorm.phonemanager.transport.grpc.TransportModule
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton

/**
 * The single application graph.
 *
 * One `@Singleton` component composes every layer's module: the use cases
 * (`ServiceModule`), the gRPC transport (`TransportModule`), the Android-free
 * adapters (`JvmAdapterModule`), the platform adapters (`AndroidAdapterModule`),
 * and the app-owned glue (`AppModule`). This is the compile-time replacement for
 * the hand-rolled `Context` registry — a missing or duplicated binding now fails
 * the BUILD, not a runtime lookup.
 *
 * ## The application Context
 *
 * The one non-module dependency is supplied through the [Factory] as a
 * `@BindsInstance` carrying the [ApplicationContext] qualifier. The graph needs a
 * real `Context` for SQLite, SharedPreferences, WifiManager and NsdManager, and
 * `Application` is the only correct one (it outlives every caller).
 *
 * ## Build the production graph
 *
 * ```
 * DaggerAppComponent.factory().create(application)
 * ```
 * (done once in `PhoneManagerApplication.onCreate`; T16 rewires consumers onto it)
 */
@Singleton
@Component(
    modules = [
        ServiceModule::class,
        TransportModule::class,
        JvmAdapterModule::class,
        AndroidAdapterModule::class,
        AppModule::class,
    ],
)
interface AppComponent {
    /**
     * The use-case surfaces. Exposed individually (rather than a `Map<Class<*>, Any>`)
     * so each consumer gets its port by type — the constructor-injection migration
     * T16 performs.
     */
    fun deviceService(): DeviceService

    fun pairingService(): PairingService

    /**
     * The interceptor's token authority. Resolving it alongside [pairingService]
     * MUST return the SAME object: [ServiceModule] binds both to one `@Singleton`
     * `PairingServiceImpl`, so the `Pair` path and the auth path can never disagree.
     */
    fun tokenVerifier(): TokenVerifier

    fun streamService(): StreamService

    /** The hub listener (netty, all interfaces in production). */
    fun hubServer(): HubServer

    /** The app-private runtime configuration the Settings screen reads and writes. */
    fun appConfig(): AppConfig

    /** Creates the graph with the application Context bound. */
    @Component.Factory
    interface Factory {
        fun create(
            @BindsInstance @ApplicationContext app: Application,
        ): AppComponent
    }
}
