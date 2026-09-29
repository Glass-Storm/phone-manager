package com.glassstorm.phonemanager.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.glassstorm.phonemanager.ui.DashboardViewModel
import com.glassstorm.phonemanager.ui.DevicesViewModel
import com.glassstorm.phonemanager.ui.PairingViewModel
import com.glassstorm.phonemanager.ui.SettingsViewModel
import com.glassstorm.phonemanager.ui.StreamViewModel
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoMap
import javax.inject.Singleton

/**
 * Binds each screen's ViewModel into the Class-keyed map [DaggerViewModelFactory]
 * resolves. Every ViewModel has an `@Inject` constructor taking its DOMAIN ports,
 * which Dagger supplies from the component graph — a missing port is a build
 * failure, not a runtime "unavailable" state.
 *
 * The bindings are deliberately UNSCOPED: a ViewModel's lifetime is owned by the
 * Compose `ViewModelStore`, so the graph must hand out a fresh instance per
 * `create` rather than share one process-wide.
 */
@Module
abstract class ViewModelModule {
    @Binds
    @IntoMap
    @ViewModelKey(DashboardViewModel::class)
    abstract fun bindDashboardViewModel(impl: DashboardViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(DevicesViewModel::class)
    abstract fun bindDevicesViewModel(impl: DevicesViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(PairingViewModel::class)
    abstract fun bindPairingViewModel(impl: PairingViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(SettingsViewModel::class)
    abstract fun bindSettingsViewModel(impl: SettingsViewModel): ViewModel

    @Binds
    @IntoMap
    @ViewModelKey(StreamViewModel::class)
    abstract fun bindStreamViewModel(impl: StreamViewModel): ViewModel

    @Binds
    @Singleton
    abstract fun bindViewModelFactory(impl: DaggerViewModelFactory): ViewModelProvider.Factory
}
