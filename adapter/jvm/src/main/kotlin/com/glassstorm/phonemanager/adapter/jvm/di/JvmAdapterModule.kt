package com.glassstorm.phonemanager.adapter.jvm.di

import com.glassstorm.phonemanager.adapter.jvm.relay.DiscardingFrameSink
import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Dagger bindings for the Android-free adapters.
 *
 * [FrameSink] is a `@Provides` rather than a `@Binds` because [DiscardingFrameSink]
 * carries no `@Inject` constructor; the provider names it explicitly so the v1
 * "accept and drop" choice is visible at the binding site. It is `@Singleton` to
 * match the registry, which registered one sink for the whole process.
 *
 * The speech engine is no longer selected here: the hub runs a single on-device
 * engine behind `SpeechPort` (bound by [AiModule]), and the legacy cloud `SttPort`
 * has been retired with the cloud STT path.
 */
@Module
object JvmAdapterModule {
    /** The v1 video sink: accept the opaque NAL and keep no state. */
    @Provides
    @Singleton
    fun provideFrameSink(): FrameSink = DiscardingFrameSink()
}
