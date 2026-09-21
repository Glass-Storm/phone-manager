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
    private lateinit var context: Context
    private lateinit var store: RuntimeConfigStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences(RuntimeConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = RuntimeConfigStore(context)
    }

    @Test
    fun `the factory returns the offline mock by default`() {
        val port = SttFactory(store).createSttPort()

        assertThat(port).isInstanceOf(MockSttAdapter::class.java)
    }

    @Test
    fun `the factory returns the speechmatics adapter when configured`() {
        store.setSttAdapterKind(SttAdapterKind.SPEECHMATICS)
        store.setApiKey("test-key-not-used")

        val port = SttFactory(store).createSttPort()

        assertThat(port).isInstanceOf(SpeechmaticsSttAdapter::class.java)
    }

    @Test
    fun `the selected mock engine actually transcribes without a network`() =
        runBlocking {
            val port = SttFactory(store).createSttPort()
            val audio = ByteArray(320) { (it % 97).toByte() }

            val text = port.transcribe("session-1", audio, 16_000)

            assertThat(text).isNotNull()
        }

    @Test
    fun `a speechmatics engine with no api key transcribes nothing and never connects`() =
        runBlocking {
            store.setSttAdapterKind(SttAdapterKind.SPEECHMATICS)
            val port = SttFactory(store).createSttPort()

            // No key => an ordinary "no utterance" outcome, NOT a hang and NOT a throw.
            // A socket attempt here would block or fail loudly, so this asserts the
            // engine stays offline until it is genuinely configured.
            val text = port.transcribe("session-1", ByteArray(320) { 1 }, 16_000)

            assertThat(text).isNull()
        }

    @Test
    fun `closing a speechmatics engine session with no api key is a no-op`() =
        runBlocking {
            store.setSttAdapterKind(SttAdapterKind.SPEECHMATICS)
            val port = SttFactory(store).createSttPort()

            port.close("session-1")
            port.close("session-1")

            assertThat(port).isInstanceOf(SpeechmaticsSttAdapter::class.java)
        }
}
