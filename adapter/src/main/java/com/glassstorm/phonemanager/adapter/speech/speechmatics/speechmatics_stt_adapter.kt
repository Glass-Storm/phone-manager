package com.glassstorm.phonemanager.adapter.speech.speechmatics

import com.glassstorm.phonemanager.domain.adapter.speech.SttPort
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/** The reference client's wait before treating a chunk as "no utterance". */
private const val GO_DEFAULT_RESULT_WAIT_MS: Long = 250L

private const val GO_NORMAL_CLOSURE: Int = 1000

/** Everything the cloud engine needs to authenticate and address the service. */
data class SpeechmaticsConfig(
    val GoApiKey: String,
    val GoRegion: String = "us",
    val GoLanguage: String = GO_DEFAULT_LANGUAGE,
)

/**
 * The injectable I/O half of the adapter.
 *
 * Kept as a separate value so the session bookkeeping (lazy connect, audio
 * encoding, idempotent teardown) is exercisable with NO socket, and so a unit
 * test can never be forced onto the network.
 */
class SpeechmaticsTransport(
    val GoTokenFetcher: GoTokenFetcher = HttpTokenFetcher(),
    val GoSocketOpener: GoSocketOpener = OkHttpSocketOpener(),
    val GoResultWaitMs: Long = GO_DEFAULT_RESULT_WAIT_MS,
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
 * selecting this engine costs nothing until audio actually arrives. [GoClose]
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
    private val GoConfig: SpeechmaticsConfig,
    private val GoTransport: SpeechmaticsTransport = SpeechmaticsTransport(),
) : SttPort {

    private val GoSessions: ConcurrentHashMap<String, GoSession> = ConcurrentHashMap()

    override suspend fun GoTranscribe(
        sessionId: String,
        audioPcm16: ByteArray,
        sampleRateHz: Int,
    ): String? {
        // No key => no network, no session, an ordinary "no utterance".
        if (GoConfig.GoApiKey.isBlank()) return null
        val GoSession = GoSessions.computeIfAbsent(sessionId) { GoSession() }
        return withContext(Dispatchers.IO) {
            if (!GoEnsureConnected(GoSession)) return@withContext null
            val GoSocket = GoSession.GoSocket ?: return@withContext null
            if (!GoSocket.send(GoPcm16ToFloat32Le(audioPcm16).toByteString())) return@withContext null
            GoSession.GoTranscripts.poll(GoTransport.GoResultWaitMs, TimeUnit.MILLISECONDS)
        }
    }

    override suspend fun GoClose(sessionId: String) {
        val GoSession = GoSessions.remove(sessionId) ?: return
        GoSession.GoListener.GoDetach()
        val GoSocket = GoSession.GoSocket ?: return
        withContext(Dispatchers.IO) {
            GoSession.GoProviderId.get()?.let { GoSocket.send(GoStopRecognitionJson(it)) }
            // close() is idempotent in OkHttp: a second call is a no-op.
            GoSocket.close(GO_NORMAL_CLOSURE, null)
        }
    }

    /** Adapter-local observability (mirrors T8/T11 accessors): a session holds live state. */
    fun GoIsSessionOpen(sessionId: String): Boolean = GoSessions.containsKey(sessionId)

    private fun GoEnsureConnected(session: GoSession): Boolean {
        if (session.GoSocket != null) return true
        val GoJwt = GoTransport.GoTokenFetcher.GoFetch(GoConfig.GoApiKey) ?: return false
        val GoUrl = "${GoRegionToWsUrl(GoConfig.GoRegion)}?jwt=$GoJwt"
        val GoSocket = GoTransport.GoSocketOpener.GoOpen(GoUrl, session.GoListener)
        session.GoSocket = GoSocket
        return true
    }

    /** Per-session state shared with [GoSpeechmaticsListener] callbacks. */
    private class GoSession {
        val GoTranscripts: LinkedBlockingQueue<String> = LinkedBlockingQueue()
        val GoProviderId: AtomicReference<String?> = AtomicReference(null)
        val GoListener: GoSpeechmaticsListener = GoSpeechmaticsListener(GoTranscripts, GoProviderId)

        @Volatile var GoSocket: WebSocket? = null
    }

    /** Bridges OkHttp's callbacks into the session's result queue. */
    private class GoSpeechmaticsListener(
        private val GoTranscripts: LinkedBlockingQueue<String>,
        private val GoProviderId: AtomicReference<String?>,
    ) : WebSocketListener() {

        @Volatile private var GoDetached = false

        /** Stop accepting results (the session is being torn down). */
        fun GoDetach() {
            GoDetached = true
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (GoDetached) return
            webSocket.send(GoStartRecognitionJson())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (GoDetached) return
            GoProviderSessionId(text)?.let { GoProviderId.set(it) }
            GoTranscriptFromMessage(text)?.let { GoTranscripts.offer(it) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // A dropped uplink is an ordinary outcome for this port: no throw, the
            // caller simply gets no transcript. The failure is deliberately not
            // logged: the socket URL carries the short-lived jwt query parameter.
            GoDetached = true
        }
    }
}
