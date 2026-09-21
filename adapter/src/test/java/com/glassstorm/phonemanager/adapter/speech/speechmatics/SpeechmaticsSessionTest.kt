package com.glassstorm.phonemanager.adapter.speech.speechmatics

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

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
    private class FakeSocket : WebSocket {
        val binaryFrames: MutableList<ByteString> = mutableListOf()
        val textFrames: MutableList<String> = mutableListOf()
        val closeCount = AtomicInteger()
        private var listener: WebSocketListener? = null

        fun attach(listener: WebSocketListener) {
            this.listener = listener
        }

        /** Simulate the provider acknowledging the handshake. */
        fun open() {
            listener?.onOpen(
                this,
                Response
                    .Builder()
                    .request(
                        okhttp3.Request
                            .Builder()
                            .url("wss://example.invalid")
                            .build(),
                    ).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(101)
                    .message("switching protocols")
                    .build(),
            )
        }

        /** Simulate a complete-utterance result frame from the provider. */
        fun emit(text: String) {
            listener?.onMessage(this, """{"message":"AddTranscript","results":[{"alternatives":[{"content":"$text"}]}]}""")
        }

        override fun request(): okhttp3.Request =
            okhttp3.Request
                .Builder()
                .url("wss://example.invalid")
                .build()

        override fun queueSize(): Long = 0

        override fun send(text: String): Boolean {
            textFrames.add(text)
            return true
        }

        override fun send(bytes: ByteString): Boolean {
            binaryFrames.add(bytes)
            return true
        }

        override fun close(
            code: Int,
            reason: String?,
        ): Boolean {
            closeCount.incrementAndGet()
            return true
        }

        override fun cancel() = Unit
    }

    private class FakeTransport(
        val sockets: MutableList<FakeSocket> = mutableListOf(),
    ) {
        val transport: SpeechmaticsTransport =
            SpeechmaticsTransport(
                tokenFetcher = TokenFetcher { "fake-jwt" },
                socketOpener =
                    SocketOpener { _, listener ->
                        FakeSocket().also {
                            it.attach(listener)
                            sockets.add(it)
                        }
                    },
                resultWaitMs = 10L,
            )
    }

    private fun adapter(transport: FakeTransport) =
        SpeechmaticsSttAdapter(
            SpeechmaticsConfig(apiKey = "fake-key", region = "eu"),
            transport.transport,
        )

    @Test
    fun `no socket is opened until the first audio chunk arrives`() =
        runBlocking {
            val transport = FakeTransport()
            val adapter = adapter(transport)

            assertThat(transport.sockets).isEmpty()
            assertThat(adapter.isSessionOpen("s1")).isFalse()
        }

    @Test
    fun `the first chunk connects, handshakes and sends encoded audio`() =
        runBlocking {
            val transport = FakeTransport()
            val adapter = adapter(transport)

            adapter.transcribe("s1", ByteArray(4) { 1 }, 16_000)

            assertThat(transport.sockets).hasSize(1)
            val socket = transport.sockets.first()
            socket.open()
            assertThat(socket.textFrames).hasSize(1)
            assertThat(socket.textFrames.first()).contains("StartRecognition")
            // Two PCM16 samples become one 8-byte float32 frame.
            assertThat(socket.binaryFrames).hasSize(1)
            assertThat(socket.binaryFrames.first().size).isEqualTo(8)
        }

    @Test
    fun `a complete utterance is returned to the caller`() =
        runBlocking {
            val transport = FakeTransport()
            val adapter = adapter(transport)
            adapter.transcribe("s1", ByteArray(4) { 1 }, 16_000)
            val socket = transport.sockets.first()
            socket.open()
            socket.emit("hello world")

            val result = adapter.transcribe("s1", ByteArray(4) { 1 }, 16_000)

            assertThat(result).isEqualTo("hello world")
        }

    @Test
    fun `a second chunk reuses the existing socket`() =
        runBlocking {
            val transport = FakeTransport()
            val adapter = adapter(transport)

            adapter.transcribe("s1", ByteArray(4) { 1 }, 16_000)
            adapter.transcribe("s1", ByteArray(4) { 1 }, 16_000)

            assertThat(transport.sockets).hasSize(1)
        }

    @Test
    fun `close is idempotent and sends StopRecognition exactly once`() =
        runBlocking {
            val transport = FakeTransport()
            val adapter = adapter(transport)
            adapter.transcribe("s1", ByteArray(4) { 1 }, 16_000)
            val socket = transport.sockets.first()
            socket.open()

            adapter.close("s1")
            adapter.close("s1")

            assertThat(socket.closeCount.get()).isEqualTo(1)
            assertThat(adapter.isSessionOpen("s1")).isFalse()
        }

    @Test
    fun `closing a session that never connected is a no-op`() =
        runBlocking {
            val transport = FakeTransport()
            val adapter = adapter(transport)

            adapter.close("never-opened")

            assertThat(transport.sockets).isEmpty()
        }

    @Test
    fun `a failed token exchange yields null without opening a socket`() =
        runBlocking {
            val transport =
                SpeechmaticsTransport(
                    tokenFetcher = TokenFetcher { null },
                    socketOpener = SocketOpener { _, _ -> error("must not be called") },
                    resultWaitMs = 10L,
                )
            val adapter =
                SpeechmaticsSttAdapter(
                    SpeechmaticsConfig(apiKey = "fake-key", region = "eu"),
                    transport,
                )

            val result = adapter.transcribe("s1", ByteArray(4) { 1 }, 16_000)

            assertThat(result).isNull()
            assertThat(adapter.isSessionOpen("s1")).isTrue()
        }
}
