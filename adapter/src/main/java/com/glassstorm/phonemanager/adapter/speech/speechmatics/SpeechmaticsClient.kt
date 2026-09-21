package com.glassstorm.phonemanager.adapter.speech.speechmatics

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/*
 * The two OkHttp seams of the Speechmatics adapter.
 *
 * They exist so the adapter's session bookkeeping (lazy connect, idempotent
 * close, transcript correlation) is testable and so a test can NEVER be forced
 * into a real socket. The production implementations below are the only place
 * that touches the network.
 */

/** Exchanges an API key for a short-lived realtime JWT. Returns `null` on failure. */
fun interface TokenFetcher {
    fun fetch(apiKey: String): String?
}

/** Opens the realtime WebSocket for an already-authenticated URL. */
fun interface SocketOpener {
    fun open(
        url: String,
        listener: WebSocketListener,
    ): WebSocket
}

/** Bounded timeouts for the cloud path (the reference client used 30s). */
private const val TIMEOUT_SECONDS: Long = 30L

/** Connect timeout for the realtime socket; bounds a hung handshake. */
private const val WS_CONNECT_TIMEOUT_SECONDS: Long = 15L

/** A 60-minute read timeout keeps an idle session from being reaped mid-utterance. */
private const val WS_READ_TIMEOUT_SECONDS: Long = 60L

/** The shared OkHttp client; connection pooling is desirable across sessions. */
fun speechmaticsHttpClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

/**
 * Production [TokenFetcher]: `POST https://mp.speechmatics.com/v1/api_keys?type=rt`
 * with `Authorization: Bearer <api_key>` and a `{"ttl":600}` body.
 *
 * The key travels ONLY in the request header — never a URL, never a log line.
 */
class HttpTokenFetcher(
    private val http: OkHttpClient = speechmaticsHttpClient(),
) : TokenFetcher {
    override fun fetch(apiKey: String): String? {
        val body = """{"ttl":$TOKEN_TTL_SECONDS}"""
        val request =
            Request
                .Builder()
                .url(TOKEN_URL)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        return try {
            http.newCall(request).execute().use { response: Response ->
                val text = response.body?.string() ?: return null
                if (!response.isSuccessful) return null
                jwtFromTokenResponse(text)
            }
        } catch (network: java.io.IOException) {
            // Ordinary connectivity failure: the port's contract is a `null`
            // outcome, not a crash. The caller decides whether to retry.
            null
        }
    }
}

/** Production [SocketOpener] over OkHttp's WebSocket transport. */
class OkHttpSocketOpener(
    private val http: OkHttpClient = speechmaticsHttpClient(),
) : SocketOpener {
    override fun open(
        url: String,
        listener: WebSocketListener,
    ): WebSocket =
        http
            .newBuilder()
            .connectTimeout(WS_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(WS_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
            .newWebSocket(Request.Builder().url(url).build(), listener)
}
