package com.glassstorm.phonemanager.adapter.speech.speechmatics

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Session-lifecycle tests for [SpeechmaticsSttAdapter] with a FAKE transport.
 *
 * No socket is opened and no key is used: the adapter's own bookkeeping (lazy
 * connect, audio encoding, idempotent teardown) is the subject. This is where the
 * cloud path is exercised at all without a live service — the acceptance gate
 * remains [MockSttAdapter]. Robolectric supplies the real `org.json` the adapter
 * uses to build its handshake frame.
 */
@RunWith(RobolectricTestRunner::class)
class SpeechmaticsSessionTest {

    private class GoFakeSocket : WebSocket {
        val GoBinaryFrames: MutableList<ByteString> = mutableListOf()
        val GoTextFrames: MutableList<String> = mutableListOf()
        val GoCloseCount = AtomicInteger()
        private var GoListener: WebSocketListener? = null

        fun GoAttach(listener: WebSocketListener) {
            GoListener = listener
        }

        /** Simulate the provider acknowledging the handshake. */
        fun GoOpen() {
            GoListener?.onOpen(this, Response.Builder()
                .request(okhttp3.Request.Builder().url("wss://example.invalid").build())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(101)
                .message("switching protocols")
                .build())
        }

        /** Simulate a complete-utterance result frame from the provider. */
        fun GoEmit(text: String) {
            GoListener?.onMessage(this, """{"message":"AddTranscript","results":[{"alternatives":[{"content":"$text"}]}]}""")
        }

        override fun request(): okhttp3.Request = okhttp3.Request.Builder().url("wss://example.invalid").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            GoTextFrames.add(text)
            return true
        }
        override fun send(bytes: ByteString): Boolean {
            GoBinaryFrames.add(bytes)
            return true
        }
        override fun close(code: Int, reason: String?): Boolean {
            GoCloseCount.incrementAndGet()
            return true
        }
        override fun cancel() = Unit
    }

    private class GoFakeTransport(
        val GoSockets: MutableList<GoFakeSocket> = mutableListOf(),
    ) {
        val GoTransport: SpeechmaticsTransport = SpeechmaticsTransport(
            GoTokenFetcher = GoTokenFetcher { "fake-jwt" },
            GoSocketOpener = GoSocketOpener { _, listener ->
                GoFakeSocket().also { it.GoAttach(listener); GoSockets.add(it) }
            },
            GoResultWaitMs = 10L,
        )
    }

    private fun GoAdapter(transport: GoFakeTransport) = SpeechmaticsSttAdapter(
        SpeechmaticsConfig(GoApiKey = "fake-key", GoRegion = "eu"),
        transport.GoTransport,
    )

    @Test
    fun `no socket is opened until the first audio chunk arrives`() = runBlocking {
        val GoTransport = GoFakeTransport()
        val GoAdapter = GoAdapter(GoTransport)

        assertThat(GoTransport.GoSockets).isEmpty()
        assertThat(GoAdapter.GoIsSessionOpen("s1")).isFalse()
    }

    @Test
    fun `the first chunk connects, handshakes and sends encoded audio`() = runBlocking {
        val GoTransport = GoFakeTransport()
        val GoAdapter = GoAdapter(GoTransport)

        GoAdapter.GoTranscribe("s1", ByteArray(4) { 1 }, 16_000)

        assertThat(GoTransport.GoSockets).hasSize(1)
        val GoSocket = GoTransport.GoSockets.first()
        GoSocket.GoOpen()
        assertThat(GoSocket.GoTextFrames).hasSize(1)
        assertThat(GoSocket.GoTextFrames.first()).contains("StartRecognition")
        // Two PCM16 samples become one 8-byte float32 frame.
        assertThat(GoSocket.GoBinaryFrames).hasSize(1)
        assertThat(GoSocket.GoBinaryFrames.first().size).isEqualTo(8)
    }

    @Test
    fun `a complete utterance is returned to the caller`() = runBlocking {
        val GoTransport = GoFakeTransport()
        val GoAdapter = GoAdapter(GoTransport)
        GoAdapter.GoTranscribe("s1", ByteArray(4) { 1 }, 16_000)
        val GoSocket = GoTransport.GoSockets.first()
        GoSocket.GoOpen()
        GoSocket.GoEmit("hello world")

        val GoResult = GoAdapter.GoTranscribe("s1", ByteArray(4) { 1 }, 16_000)

        assertThat(GoResult).isEqualTo("hello world")
    }

    @Test
    fun `a second chunk reuses the existing socket`() = runBlocking {
        val GoTransport = GoFakeTransport()
        val GoAdapter = GoAdapter(GoTransport)

        GoAdapter.GoTranscribe("s1", ByteArray(4) { 1 }, 16_000)
        GoAdapter.GoTranscribe("s1", ByteArray(4) { 1 }, 16_000)

        assertThat(GoTransport.GoSockets).hasSize(1)
    }

    @Test
    fun `close is idempotent and sends StopRecognition exactly once`() = runBlocking {
        val GoTransport = GoFakeTransport()
        val GoAdapter = GoAdapter(GoTransport)
        GoAdapter.GoTranscribe("s1", ByteArray(4) { 1 }, 16_000)
        val GoSocket = GoTransport.GoSockets.first()
        GoSocket.GoOpen()

        GoAdapter.GoClose("s1")
        GoAdapter.GoClose("s1")

        assertThat(GoSocket.GoCloseCount.get()).isEqualTo(1)
        assertThat(GoAdapter.GoIsSessionOpen("s1")).isFalse()
    }

    @Test
    fun `closing a session that never connected is a no-op`() = runBlocking {
        val GoTransport = GoFakeTransport()
        val GoAdapter = GoAdapter(GoTransport)

        GoAdapter.GoClose("never-opened")

        assertThat(GoTransport.GoSockets).isEmpty()
    }

    @Test
    fun `a failed token exchange yields null without opening a socket`() = runBlocking {
        val GoTransport = SpeechmaticsTransport(
            GoTokenFetcher = GoTokenFetcher { null },
            GoSocketOpener = GoSocketOpener { _, _ -> error("must not be called") },
            GoResultWaitMs = 10L,
        )
        val GoAdapter = SpeechmaticsSttAdapter(
            SpeechmaticsConfig(GoApiKey = "fake-key", GoRegion = "eu"),
            GoTransport,
        )

        val GoResult = GoAdapter.GoTranscribe("s1", ByteArray(4) { 1 }, 16_000)

        assertThat(GoResult).isNull()
        assertThat(GoAdapter.GoIsSessionOpen("s1")).isTrue()
    }
}
