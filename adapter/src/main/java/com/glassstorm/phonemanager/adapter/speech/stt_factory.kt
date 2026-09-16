package com.glassstorm.phonemanager.adapter.speech

import com.glassstorm.phonemanager.adapter.config.RuntimeConfigStore
import com.glassstorm.phonemanager.adapter.config.SttAdapterKind
import com.glassstorm.phonemanager.adapter.speech.mock.MockSttAdapter
import com.glassstorm.phonemanager.adapter.speech.speechmatics.SpeechmaticsConfig
import com.glassstorm.phonemanager.adapter.speech.speechmatics.SpeechmaticsSttAdapter
import com.glassstorm.phonemanager.adapter.speech.speechmatics.SpeechmaticsTransport
import com.glassstorm.phonemanager.domain.adapter.speech.SttPort

/**
 * Selects the speech engine from app configuration.
 *
 * The composition root calls [GoCreateSttPort] ONCE and registers the result in
 * the Context under the [SttPort] interface; `:service` never learns which engine
 * it is talking to. The DEFAULT (no configuration written yet) is the offline
 * [MockSttAdapter], so a fresh install always transcribes deterministically.
 *
 * The cloud engine is constructed EAGERLY but CONNECTS lazily on its first audio
 * chunk, so selecting it costs nothing until audio arrives.
 *
 * Settings UI for these keys is deliberately out of scope here (T17).
 */
class SttFactory(
    private val GoConfig: RuntimeConfigStore,
    private val GoTransport: SpeechmaticsTransport = SpeechmaticsTransport(),
) {
    /** Build the configured engine. */
    fun GoCreateSttPort(): SttPort =
        when (GoConfig.GoSttAdapterKind()) {
            SttAdapterKind.MOCK -> MockSttAdapter()
            SttAdapterKind.SPEECHMATICS ->
                SpeechmaticsSttAdapter(
                    SpeechmaticsConfig(
                        GoApiKey = GoConfig.GoApiKey(),
                        GoRegion = GoConfig.GoRegion(),
                    ),
                    GoTransport,
                )
        }
}
