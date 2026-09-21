package com.glassstorm.phonemanager.adapter.android.di

import android.content.Context
import com.glassstorm.phonemanager.adapter.android.config.RuntimeConfigStore
import com.glassstorm.phonemanager.adapter.android.network.nsd.NsdDiscoveryAdapter
import com.glassstorm.phonemanager.adapter.android.network.wifi.LocalOnlyHotspotAdapter
import com.glassstorm.phonemanager.adapter.android.repository.sqlite.SqliteDeviceRepository
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.domain.adapter.network.Discovery
import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Dagger bindings for the Android-backed adapters.
 *
 * Every provider takes the [ApplicationContext]-qualified `Context` and constructs
 * the adapter with its PRODUCTION tuning: the adapters' own Kotlin default
 * arguments (database name, hotspot launcher/probe) are invisible to Dagger, so
 * they are applied here in the provider body where Kotlin default arguments are
 * legal and explicit.
 *
 * All bindings are `@Singleton` to match the registry's one-instance-per-port
 * semantics: one SQLite handle, one hotspot reservation owner, one NSD advertiser,
 * and one config store the settings screen and the STT factory both read.
 *
 * ## Deliberately absent
 *
 * [com.glassstorm.phonemanager.battery.BatteryExemption] is `:app`-owned platform
 * glue and is bound in `:app`'s own module. `:adapter:android` must NEVER depend
 * on `:app`, so its binding cannot live here.
 */
@Module
object AndroidAdapterModule {
    /** The persistent device repository: SQLite, token HASHES only. */
    @Provides
    @Singleton
    fun provideDeviceRepository(
        @ApplicationContext context: Context,
    ): DeviceRepository = SqliteDeviceRepository(context)

    /** App-private runtime configuration (STT engine, API key, region, hotspot mode). */
    @Provides
    @Singleton
    fun provideAppConfig(
        @ApplicationContext context: Context,
    ): AppConfig = RuntimeConfigStore(context)

    /**
     * The access-point controller. The gateway fallback in discovery is a
     * settings-owned value left unconfigured (`null`): the hub's own use of the
     * port is advertising, which needs no fallback.
     */
    @Provides
    @Singleton
    fun provideHotspotController(
        @ApplicationContext context: Context,
    ): HotspotController = LocalOnlyHotspotAdapter(context)

    /** DNS-SD advertiser over NsdManager; the mDNS-miss gateway fallback is unset. */
    @Provides
    @Singleton
    fun provideDiscovery(
        @ApplicationContext context: Context,
    ): Discovery = NsdDiscoveryAdapter(context, gatewayFallback = null)
}
