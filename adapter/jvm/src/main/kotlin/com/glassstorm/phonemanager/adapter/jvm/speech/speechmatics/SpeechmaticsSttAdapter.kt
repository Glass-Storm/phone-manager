package com.glassstorm.phonemanager.adapter.jvm.speech.speechmatics

import com.glassstorm.phonemanager.core.domain.adapter.speech.SttPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The reference client's wait before treating a chunk as "no utterance". */
private const val DEFAULT_RESULT_WAIT_MS: Long = 250L

// Public so ConstantValuesTest can assert the WebSocket close code literal directly.
const val NORMAL_CLOSURE: Int = 1000

/** Everything the cloud engine needs to authenticate and address the service. */
data class SpeechmaticsConfig(
    val apiKey: String,
    val region: String = "us",
    val language: String = DEFAULT_LANGUAGE,
)

/**
 * The injectable I/O half of the adapter.
 *
 * Kept as a separate value so the session bookkeeping (lazy connect, audio
 * encoding, idempotent teardown) is exercisable with NO socket, and so a unit
 * test can never be forced onto the network.
 */
class SpeechmaticsTransport(
    val tokenFetcher: TokenFetcher = HttpTokenFetcher(),
    val socketOpener: SocketOpener = OkHttpSocketOpener(),
    val resultWaitMs: Long = DEFAULT_RESULT_WAIT_MS,
)

/**
 * Speechmatics realtime speech-to-text, config-selectable and explicitly NOT a
 * v1 acceptance gate (issues.md R4).
 *
 * Ported from the reference client's proven flow: JWT via the token endpoint,
 * `wss://{region}.rt.speechmatics.com/v2?jwt=…`, `StartRecognition` with raw
 * `pcm_f32le` at 16 kHz, BINARY frames for audio, TEXT frames for results.
 *
 * ## Lazy, per-session lifecycle
 *
 * A session connects on its FIRST audio chunk (never in the constructor), so
 * selecting this engine costs nothing until audio actually arrives. [close]
 * sends the provider `StopRecognition` frame and closes the socket; it is
 * idempotent and tolerates a session that never connected.
 *
 * ## Failure posture
 *
 * A missing key, a failed token exchange, a socket error and a wait with no
 * complete utterance ALL surface as the port's ordinary `null` result — never a
 * throw. The wait is BOUNDED, so a stalled uplink can never hang the relay.
 *
 * The API key is used only in the `Authorization` header and is never logged,
 * never placed in a URL and never written anywhere but the app-private store.
 */
class SpeechmaticsSttAdapter(
    private val config: SpeechmaticsConfig,
    private val transport: SpeechmaticsTransport = SpeechmaticsTransport(),
) : SttPort {
    private val sessions: ConcurrentHashMap<String, Session> = ConcurrentHashMap()

    override suspend fun transcribe(
        sessionId: String,
        audioPcm16: ByteArray,
        sampleRateHz: Int,
    ): String? {
        // No key => no network, no session, an ordinary "no utterance".
        if (config.apiKey.isBlank()) return null
        val session = sessions.computeIfAbsent(sessionId) { Session() }
        return withContext(Dispatchers.IO) {
            if (!ensureConnected(session)) return@withContext null
            val socket = session.socket ?: return@withContext null
            if (!socket.send(pcm16ToFloat32Le(audioPcm16).toByteString())) return@withContext null
            session.transcripts.poll(transport.resultWaitMs, TimeUnit.MILLISECONDS)
        }
    }

    override suspend fun close(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        session.listener.detach()
        val socket = session.socket ?: return
        withContext(Dispatchers.IO) {
            session.providerId.get()?.let { socket.send(stopRecognitionJson(it)) }
            // close() is idempotent in OkHttp: a second call is a no-op.
            socket.close(NORMAL_CLOSURE, null)
        }
    }

    /** Adapter-local observability (mirrors T8/T11 accessors): a session holds live state. */
    fun isSessionOpen(sessionId: String): Boolean = sessions.containsKey(sessionId)

    private fun ensureConnected(session: Session): Boolean {
        if (session.socket != null) return true
        val jwt = transport.tokenFetcher.fetch(config.apiKey) ?: return false
        val url = "${regionToWsUrl(config.region)}?jwt=$jwt"
        val socket = transport.socketOpener.open(url, session.listener)
        session.socket = socket
        return true
    }

    /** Per-session state shared with [SpeechmaticsListener] callbacks. */
    private class Session {
        val transcripts: LinkedBlockingQueue<String> = LinkedBlockingQueue()
        val providerId: AtomicReference<String?> = AtomicReference(null)
        val listener: SpeechmaticsListener = SpeechmaticsListener(transcripts, providerId)

        @Volatile var socket: WebSocket? = null
    }

    /** Bridges OkHttp's callbacks into the session's result queue. */
    private class SpeechmaticsListener(
        private val transcripts: LinkedBlockingQueue<String>,
        private val providerId: AtomicReference<String?>,
    ) : WebSocketListener() {
        @Volatile private var detached = false

        /** Stop accepting results (the session is being torn down). */
        fun detach() {
            detached = true
        }

        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            if (detached) return
            webSocket.send(startRecognitionJson())
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (detached) return
            providerSessionId(text)?.let { providerId.set(it) }
            transcriptFromMessage(text)?.let { transcripts.offer(it) }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            // A dropped uplink is an ordinary outcome for this port: no throw, the
            // caller simply gets no transcript. The failure is deliberately not
            // logged: the socket URL carries the short-lived jwt query parameter.
            detached = true
        }
    }
}
