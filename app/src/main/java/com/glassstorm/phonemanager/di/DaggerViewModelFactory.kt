package com.glassstorm.phonemanager.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import javax.inject.Inject
import javax.inject.Provider

/**
 * The single [ViewModelProvider.Factory] the UI uses.
 *
 * The ViewModel graph is a Dagger multibinding: every screen's ViewModel is bound
 * into `Map<Class<out ViewModel>, Provider<ViewModel>>` by [ViewModelModule], and
 * this factory resolves the requested class against that map. A screen whose
 * ViewModel is not bound therefore fails on the first `create` rather than
 * silently constructing an unwired object.
 *
 * A [Provider] (not the instance) is stored, so each `create` yields a fresh
 * ViewModel and the `ViewModelStore` — not the graph — owns its lifetime.
 */
class DaggerViewModelFactory
    @Inject
    constructor(
        private val providers: Map<Class<out ViewModel>, @JvmSuppressWildcards Provider<ViewModel>>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val provider =
                providers[modelClass]
                    ?: throw IllegalArgumentException(
                        "no ViewModel bound for ${modelClass.name}",
                    )
            return provider.get() as T
        }
    }
