package com.glassstorm.phonemanager.adapter.speech.speechmatics

import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * The Speechmatics realtime WIRE FORMAT, as pure functions.
 *
 * Everything here is deliberately side-effect free (no socket, no key, no clock)
 * so the cloud adapter's protocol shape is proven on a plain JVM. The live
 * WebSocket handshake cannot be exercised in CI (it needs a key and the phone's
 * cellular uplink while the phone is the access point — issues.md R4), so these
 * functions ARE the acceptance surface of the cloud path.
 *
 * Ported from the reference client's proven flow
 * (`SpeechmaticsClient.java`: token endpoint, region→`wss://` map, `pcm_f32le`).
 */

/** Realtime JWT endpoint. The key travels in the `Authorization` header, never the URL. */
const val TOKEN_URL: String = "https://mp.speechmatics.com/v1/api_keys?type=rt"

/** Recognition language when the caller does not override it. */
const val DEFAULT_LANGUAGE: String = "en"

/** Sample rate the hub relays audio at ([com.glassstorm.phonemanager.domain.service.StreamService]). */
const val SAMPLE_RATE_HZ: Int = 16_000

/** Requested JWT lifetime in seconds (the reference client's value). */
const val TOKEN_TTL_SECONDS: Int = 600

private const val BYTES_PER_PCM16_SAMPLE = 2
private const val FLOAT32_BYTES = 4
private const val PCM16_SCALE = 32_768f

/**
 * Map a region name to its realtime WebSocket endpoint.
 *
 * Unknown, blank and `null` regions fall back to `us`, matching the reference
 * store's `regionToWsUrl`. Matching is trimmed and case-insensitive.
 */
fun regionToWsUrl(region: String?): String =
    when (region?.trim()?.lowercase()) {
        "global" -> "wss://global.rt.speechmatics.com/v2"
        "eu" -> "wss://eu.rt.speechmatics.com/v2"
        "au" -> "wss://au.rt.speechmatics.com/v2"
        else -> "wss://us.rt.speechmatics.com/v2"
    }

/**
 * Convert little-endian PCM16 mono to little-endian IEEE-754 float32, the only
 * encoding Speechmatics realtime accepts for raw audio.
 *
 * A trailing odd byte is ignored rather than throwing: the hub relays opaque
 * audio, so a malformed final byte MUST NOT take the session down.
 */
fun pcm16ToFloat32Le(pcm16: ByteArray): ByteArray {
    val out =
        ByteBuffer
            .allocate((pcm16.size / BYTES_PER_PCM16_SAMPLE) * FLOAT32_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
    var index = 0
    while (index + 1 < pcm16.size) {
        val low = pcm16[index].toInt() and 0xFF
        val high = pcm16[index + 1].toInt()
        out.putFloat(((high shl 8) or low).toFloat() / PCM16_SCALE)
        index += BYTES_PER_PCM16_SAMPLE
    }
    return out.array()
}

/** The `StartRecognition` handshake frame: raw `pcm_f32le` at 16 kHz. */
fun startRecognitionJson(language: String = DEFAULT_LANGUAGE): String =
    JSONObject()
        .put("message", "StartRecognition")
        .put(
            "audio_format",
            JSONObject()
                .put("type", "raw")
                .put("encoding", "pcm_f32le")
                .put("sample_rate", SAMPLE_RATE_HZ),
        ).put("transcription_config", JSONObject().put("language", language))
        .toString()

/** The `StopRecognition` teardown frame, carrying the PROVIDER's session id. */
fun stopRecognitionJson(providerSessionId: String): String =
    JSONObject()
        .put("message", "StopRecognition")
        .put("session_id", providerSessionId)
        .toString()

/** The message discriminator, or `""` when [message] is not JSON. */
fun messageName(message: String): String =
    try {
        JSONObject(message).optString("message", "")
    } catch (malformed: JSONException) {
        ""
    }

/**
 * Extract the `key_value` JWT from a token-endpoint response body, or `null`
 * when the body is malformed or carries no token.
 */
fun jwtFromTokenResponse(body: String): String? =
    try {
        JSONObject(body).optString("key_value").ifEmpty { null }
    } catch (malformed: JSONException) {
        null
    }

/** The provider's session id from a `RecognitionStarted` frame, or `null`. */
fun providerSessionId(message: String): String? =
    try {
        JSONObject(message).optString("session_id").ifEmpty { null }
    } catch (malformed: JSONException) {
        null
    }

/**
 * Join the recognized tokens of a COMPLETE utterance (`AddTranscript`), or `null`
 * for any other frame (notably `AddPartialTranscript`, which is deliberately
 * ignored: the port emits only complete utterances).
 */
fun transcriptFromMessage(message: String): String? {
    val frame =
        try {
            JSONObject(message)
        } catch (malformed: JSONException) {
            return null
        }
    if (frame.optString("message") != ADD_TRANSCRIPT) return null
    val results = frame.optJSONArray("results") ?: return null
    val builder = StringBuilder()
    for (index in 0 until results.length()) {
        val alternatives = results.optJSONObject(index)?.optJSONArray("alternatives") ?: continue
        val content = alternatives.optJSONObject(0)?.optString("content") ?: continue
        if (builder.isNotEmpty()) builder.append(' ')
        builder.append(content)
    }
    return builder.toString().ifBlank { null }
}

// Public so ConstantValuesTest can assert the wire discriminator literal directly.
const val ADD_TRANSCRIPT = "AddTranscript"
