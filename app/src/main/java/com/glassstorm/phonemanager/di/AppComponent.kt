package com.glassstorm.phonemanager.di

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import com.glassstorm.phonemanager.adapter.android.di.AndroidAdapterModule
import com.glassstorm.phonemanager.adapter.android.di.ApplicationContext
import com.glassstorm.phonemanager.adapter.jvm.di.JvmAdapterModule
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.domain.adapter.network.Discovery
import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
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
 * the app-owned glue (`AppModule`), and the ViewModel multibinding
 * (`ViewModelModule`). This is the compile-time replacement for the hand-rolled
 * `Context` registry — a missing or duplicated binding fails the BUILD, not a
 * runtime lookup.
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
 * (done once in `PhoneManagerApplication.onCreate`; consumers resolve through the
 * [PhoneManagerApplication.component] field)
 */
@Singleton
@Component(
    modules = [
        ServiceModule::class,
        TransportModule::class,
        JvmAdapterModule::class,
        AndroidAdapterModule::class,
        AppModule::class,
        ViewModelModule::class,
    ],
)
interface AppComponent {
    /**
     * The use-case surfaces. Exposed individually (rather than a `Map<Class<*>, Any>`)
     * so each consumer gets its port by type.
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

    /** The persistent device repository, so integration tests can drive the real store. */
    fun deviceRepository(): DeviceRepository

    /** The platform access-point controller the foreground service drives bring-up with. */
    fun hotspotController(): HotspotController

    /** The DNS-SD advertiser the foreground service drives bring-up with. */
    fun discovery(): Discovery

    /** The ViewModel factory the Compose shell threads down to every screen. */
    fun viewModelFactory(): ViewModelProvider.Factory

    /** Creates the graph with the application Context bound. */
    @Component.Factory
    interface Factory {
        fun create(
            @BindsInstance @ApplicationContext app: Application,
        ): AppComponent
    }
}
