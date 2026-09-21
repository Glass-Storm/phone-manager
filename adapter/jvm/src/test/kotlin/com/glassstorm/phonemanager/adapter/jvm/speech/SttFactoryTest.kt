package com.glassstorm.phonemanager.adapter.jvm.speech

import com.glassstorm.phonemanager.adapter.jvm.speech.mock.MockSttAdapter
import com.glassstorm.phonemanager.adapter.jvm.speech.speechmatics.SpeechmaticsSttAdapter
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.model.HotspotMode
import com.glassstorm.phonemanager.core.model.SttEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Tests for the engine selector.
 *
 * The factory reads the DOMAIN [AppConfig] port, so this is a PURE-JVM test: an
 * in-memory fake stands in for the Android store and no Robolectric is needed.
 * That is exactly the point of the `:adapter:jvm` split — engine selection no
 * longer drags the Android config store (and its platform dependency) into this
 * module.
 *
 * Nothing here opens a socket: the Speechmatics adapter connects LAZILY on the
 * first audio chunk, so construction and selection are pure wiring.
 */
class SttFactoryTest {
    /**
     * In-memory [AppConfig]. Only the members the factory reads are meaningful;
     * the rest are implemented so the fake is a faithful port implementation.
     */
    private class FakeAppConfig(
        var engine: SttEngine = SttEngine.MOCK,
        var key: String = "",
        var regionName: String = AppConfig.DEFAULT_REGION,
    ) : AppConfig {
        override fun sttEngine(): SttEngine = engine

        override fun setSttEngine(kind: SttEngine) {
            engine = kind
        }

        override fun apiKey(): String = key

        override fun setApiKey(apiKey: String?) {
            key = apiKey?.trim().orEmpty()
        }

        override fun region(): String = regionName

        override fun setRegion(region: String?) {
            region?.trim()?.lowercase()?.let { regionName = it }
        }

        override fun hotspotMode(): HotspotMode = HotspotMode.MANUAL

        override fun setHotspotMode(mode: HotspotMode) = Unit
    }

    private val unset = FakeAppConfig()

    @Test
    fun `the factory returns the offline mock by default`() {
        val port = SttFactory(unset).createSttPort()

        assertThat(port).isInstanceOf(MockSttAdapter::class.java)
    }

    @Test
    fun `the factory returns the speechmatics adapter when configured`() {
        val config = FakeAppConfig(engine = SttEngine.SPEECHMATICS, key = "test-key-not-used")

        val port = SttFactory(config).createSttPort()

        assertThat(port).isInstanceOf(SpeechmaticsSttAdapter::class.java)
    }

    @Test
    fun `the selected mock engine actually transcribes without a network`() =
        runBlocking {
            val port = SttFactory(unset).createSttPort()
            val audio = ByteArray(320) { (it % 97).toByte() }

            val text = port.transcribe("session-1", audio, 16_000)

            assertThat(text).isNotNull()
        }

    @Test
    fun `a speechmatics engine with no api key transcribes nothing and never connects`() =
        runBlocking {
            val config = FakeAppConfig(engine = SttEngine.SPEECHMATICS)
            val port = SttFactory(config).createSttPort()

            // No key => an ordinary "no utterance" outcome, NOT a hang and NOT a throw.
            // A socket attempt here would block or fail loudly, so this asserts the
            // engine stays offline until it is genuinely configured.
            val text = port.transcribe("session-1", ByteArray(320) { 1 }, 16_000)

            assertThat(text).isNull()
        }

    @Test
    fun `closing a speechmatics engine session with no api key is a no-op`() =
        runBlocking {
            val config = FakeAppConfig(engine = SttEngine.SPEECHMATICS)
            val port = SttFactory(config).createSttPort()

            port.close("session-1")
            port.close("session-1")

            assertThat(port).isInstanceOf(SpeechmaticsSttAdapter::class.java)
        }
}
