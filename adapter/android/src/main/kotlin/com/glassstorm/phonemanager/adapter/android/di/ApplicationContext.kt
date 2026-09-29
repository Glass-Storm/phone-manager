package com.glassstorm.phonemanager.adapter.android.di

import javax.inject.Qualifier

/**
 * The Android application [android.content.Context] binding.
 *
 * Declared HERE, in `:adapter:android`, because both sides of the graph need to
 * name it: this module's providers (SQLite, hotspot, NSD, config, battery) consume
 * it, and `:app`'s component factory supplies it as a `@BindsInstance`
 * (`@ApplicationContext app: Application`). `:core:domain` must stay
 * annotation-free, so the qualifier deliberately does NOT live there.
 *
 * It is the ONLY qualifier in the graph: a `Context` is otherwise ambiguous
 * (Activity vs Application vs Service), and Dagger cannot disambiguate it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationContext
