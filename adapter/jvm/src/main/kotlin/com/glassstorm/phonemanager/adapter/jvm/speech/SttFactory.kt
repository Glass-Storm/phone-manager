package com.glassstorm.phonemanager.adapter.jvm.speech

import com.glassstorm.phonemanager.adapter.jvm.speech.mock.MockSttAdapter
import com.glassstorm.phonemanager.adapter.jvm.speech.speechmatics.SpeechmaticsConfig
import com.glassstorm.phonemanager.adapter.jvm.speech.speechmatics.SpeechmaticsSttAdapter
import com.glassstorm.phonemanager.adapter.jvm.speech.speechmatics.SpeechmaticsTransport
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.domain.adapter.speech.SttPort
import com.glassstorm.phonemanager.core.model.SttEngine

/**
 * Selects the speech engine from app configuration.
 *
 * The composition root calls [createSttPort] ONCE and registers the result in
 * the Context under the [SttPort] interface; `:service` never learns which engine
 * it is talking to. The DEFAULT (no configuration written yet) is the offline
 * [MockSttAdapter], so a fresh install always transcribes deterministically.
 *
 * The cloud engine is constructed EAGERLY but CONNECTS lazily on its first audio
 * chunk, so selecting it costs nothing until audio arrives.
 *
 * Selection reads the DOMAIN [AppConfig] port and branches on the domain
 * [SttEngine] vocabulary, never on the Android store's private on-disk spelling:
 * this is what lets `:adapter:jvm` select the engine without a dependency on
 * `:adapter:android`.
 *
 * Settings UI for these keys is deliberately out of scope here (T17).
 */
class SttFactory(
    private val config: AppConfig,
    private val transport: SpeechmaticsTransport = SpeechmaticsTransport(),
) {
    /** Build the configured engine. */
    fun createSttPort(): SttPort =
        when (config.sttEngine()) {
            SttEngine.MOCK -> MockSttAdapter()
            SttEngine.SPEECHMATICS ->
                SpeechmaticsSttAdapter(
                    SpeechmaticsConfig(
                        apiKey = config.apiKey(),
                        region = config.region(),
                    ),
                    transport,
                )
        }
}
