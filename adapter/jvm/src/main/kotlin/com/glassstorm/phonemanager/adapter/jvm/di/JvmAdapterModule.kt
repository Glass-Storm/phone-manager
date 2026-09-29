package com.glassstorm.phonemanager.adapter.jvm.di

import com.glassstorm.phonemanager.adapter.jvm.relay.DiscardingFrameSink
import com.glassstorm.phonemanager.adapter.jvm.speech.SttFactory
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.speech.SttPort
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Dagger bindings for the Android-free adapters.
 *
 * Both ports are `@Provides` rather than `@Binds` because neither implementation
 * carries an `@Inject` constructor:
 *
 *  * [SttPort] is selected at runtime from [AppConfig] by [SttFactory] (the mock
 *    engine is the default; the Speechmatics engine is built when configured), so
 *    a provider is the only honest expression of that factory.
 *  * [FrameSink] is the stateless [DiscardingFrameSink]; the provider names it
 *    explicitly so the v1 "accept and drop" choice is visible at the binding site.
 *
 * Both are `@Singleton` to match the registry, which registered one instance each:
 * a relay session must talk to one engine and one sink for its whole life.
 */
@Module
object JvmAdapterModule {
    /**
     * The configured speech engine. Built EAGERLY as a graph node but CONNECTS
     * lazily on the first audio chunk (see [SttFactory] / the Speechmatics
     * adapter), so selecting the cloud engine costs nothing until audio arrives.
     */
    @Provides
    @Singleton
    fun provideSttPort(config: AppConfig): SttPort = SttFactory(config).createSttPort()

    /** The v1 video sink: accept the opaque NAL and keep no state. */
    @Provides
    @Singleton
    fun provideFrameSink(): FrameSink = DiscardingFrameSink()
}
