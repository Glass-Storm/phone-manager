package com.glassstorm.phonemanager

import android.app.Application
import com.glassstorm.phonemanager.di.AppComponent
import com.glassstorm.phonemanager.di.DaggerAppComponent

/**
 * The process entry point that owns the Dagger graph.
 *
 * Building the component here — once per process, before any Activity or Service
 * runs — is what makes the dependency graph process-wide: the foreground service
 * and the UI resolve the SAME `PairingServiceImpl`, `StreamServiceImpl` and
 * `HubServer` through it, so they can never observe different hub state.
 *
 * ## Transitional (T15)
 *
 * The legacy `Context` registry still exists and is still what the consumers use;
 * T16 rewires them onto this component and deletes the registry. Building the
 * graph here now is what proves it compiles and resolves alongside the registry.
 */
class PhoneManagerApplication : Application() {
    /**
     * The process-wide dependency graph. `lateinit` because it is created in
     * [onCreate]; nothing may touch it before then.
     */
    lateinit var component: AppComponent
        private set

    override fun onCreate() {
        super.onCreate()
        component = DaggerAppComponent.factory().create(this)
    }
}
