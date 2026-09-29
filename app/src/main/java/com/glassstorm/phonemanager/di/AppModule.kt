package com.glassstorm.phonemanager.di

import android.app.Application
import android.content.Context
import com.glassstorm.phonemanager.adapter.android.di.ApplicationContext
import com.glassstorm.phonemanager.battery.AndroidBatteryExemption
import com.glassstorm.phonemanager.battery.BatteryExemption
import com.glassstorm.phonemanager.hub.AndroidHubStarter
import com.glassstorm.phonemanager.hub.HubStarter
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Dagger bindings for the app-owned platform glue and the application Context.
 *
 * [BatteryExemption] -> [AndroidBatteryExemption] and [HubStarter] ->
 * [AndroidHubStarter]: these screens' seams onto Android live in `:app` (app-owned
 * platform glue, not reusable adapters), so their bindings must too.
 * `:adapter:android` must never depend on `:app`, so it could not provide them.
 *
 * [provideApplicationContext] narrows the `@BindsInstance` [Application] the
 * component factory supplies to the qualified [Context] every adapter provider
 * asks for. `@BindsInstance` binds the EXACT `Application` type, and Dagger does
 * not infer the supertype, so the explicit alias is required.
 *
 * All are `@Singleton` to match the registry's one-instance semantics.
 */
@Module
object AppModule {
    /** The application Context the platform adapters consume. */
    @Provides
    @Singleton
    @ApplicationContext
    fun provideApplicationContext(
        @ApplicationContext app: Application,
    ): Context = app

    @Provides
    @Singleton
    fun provideBatteryExemption(
        @ApplicationContext context: Context,
    ): BatteryExemption = AndroidBatteryExemption(context)

    @Provides
    @Singleton
    fun provideHubStarter(
        @ApplicationContext context: Context,
    ): HubStarter = AndroidHubStarter(context)
}
