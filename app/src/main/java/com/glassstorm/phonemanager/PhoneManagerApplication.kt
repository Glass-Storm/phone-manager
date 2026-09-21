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
 * [HubForegroundService] resolves its collaborators and [MainActivity] resolves
 * the ViewModel factory from this field; nothing builds a second graph.
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
