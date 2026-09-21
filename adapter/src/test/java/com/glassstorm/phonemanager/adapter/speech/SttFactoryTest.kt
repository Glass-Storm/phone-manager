package com.glassstorm.phonemanager.adapter.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.adapter.config.RuntimeConfigStore
import com.glassstorm.phonemanager.adapter.config.SttAdapterKind
import com.glassstorm.phonemanager.adapter.speech.mock.MockSttAdapter
import com.glassstorm.phonemanager.adapter.speech.speechmatics.SpeechmaticsSttAdapter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the engine selector.
 *
 * Nothing here opens a socket: the Speechmatics adapter connects LAZILY on the
 * first audio chunk, so construction and selection are pure wiring.
 */
@RunWith(RobolectricTestRunner::class)
class SttFactoryTest {
    private lateinit var GoContext: Context
    private lateinit var GoStore: RuntimeConfigStore

    @Before
    fun setUp() {
        GoContext = ApplicationProvider.getApplicationContext()
        GoContext
            .getSharedPreferences(RuntimeConfigStore.GO_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        GoStore = RuntimeConfigStore(GoContext)
    }

    @Test
    fun `the factory returns the offline mock by default`() {
        val GoPort = SttFactory(GoStore).GoCreateSttPort()

        assertThat(GoPort).isInstanceOf(MockSttAdapter::class.java)
    }

    @Test
    fun `the factory returns the speechmatics adapter when configured`() {
        GoStore.GoSetSttAdapterKind(SttAdapterKind.SPEECHMATICS)
        GoStore.GoSetApiKey("test-key-not-used")

        val GoPort = SttFactory(GoStore).GoCreateSttPort()

        assertThat(GoPort).isInstanceOf(SpeechmaticsSttAdapter::class.java)
    }

    @Test
    fun `the selected mock engine actually transcribes without a network`() =
        runBlocking {
            val GoPort = SttFactory(GoStore).GoCreateSttPort()
            val GoAudio = ByteArray(320) { (it % 97).toByte() }

            val GoText = GoPort.GoTranscribe("session-1", GoAudio, 16_000)

            assertThat(GoText).isNotNull()
        }

    @Test
    fun `a speechmatics engine with no api key transcribes nothing and never connects`() =
        runBlocking {
            GoStore.GoSetSttAdapterKind(SttAdapterKind.SPEECHMATICS)
            val GoPort = SttFactory(GoStore).GoCreateSttPort()

            // No key => an ordinary "no utterance" outcome, NOT a hang and NOT a throw.
            // A socket attempt here would block or fail loudly, so this asserts the
            // engine stays offline until it is genuinely configured.
            val GoText = GoPort.GoTranscribe("session-1", ByteArray(320) { 1 }, 16_000)

            assertThat(GoText).isNull()
        }

    @Test
    fun `closing a speechmatics engine session with no api key is a no-op`() =
        runBlocking {
            GoStore.GoSetSttAdapterKind(SttAdapterKind.SPEECHMATICS)
            val GoPort = SttFactory(GoStore).GoCreateSttPort()

            GoPort.GoClose("session-1")
            GoPort.GoClose("session-1")

            assertThat(GoPort).isInstanceOf(SpeechmaticsSttAdapter::class.java)
        }
}
