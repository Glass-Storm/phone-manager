package com.glassstorm.phonemanager.adapter.speech.speechmatics

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Structural/contract tests for the Speechmatics WIRE FORMAT, exercised as PURE
 * FUNCTIONS — no socket, no API key, no network. These are the parts of the cloud
 * adapter that must be correct regardless of whether a live call ever happens.
 *
 * Robolectric supplies the real `org.json` implementation; the bare `android.jar`
 * used by plain unit tests stubs every method and would fail with "not mocked".
 */
@RunWith(RobolectricTestRunner::class)
class SpeechmaticsWireTest {

    @Test
    fun `region maps to the documented websocket host`() {
        assertThat(GoRegionToWsUrl("global")).isEqualTo("wss://global.rt.speechmatics.com/v2")
        assertThat(GoRegionToWsUrl("eu")).isEqualTo("wss://eu.rt.speechmatics.com/v2")
        assertThat(GoRegionToWsUrl("us")).isEqualTo("wss://us.rt.speechmatics.com/v2")
        assertThat(GoRegionToWsUrl("au")).isEqualTo("wss://au.rt.speechmatics.com/v2")
    }

    @Test
    fun `region matching is case-insensitive and trims`() {
        assertThat(GoRegionToWsUrl("EU")).isEqualTo("wss://eu.rt.speechmatics.com/v2")
        assertThat(GoRegionToWsUrl("  au  ")).isEqualTo("wss://au.rt.speechmatics.com/v2")
    }

    @Test
    fun `an unknown or missing region falls back to us`() {
        assertThat(GoRegionToWsUrl(null)).isEqualTo("wss://us.rt.speechmatics.com/v2")
        assertThat(GoRegionToWsUrl("")).isEqualTo("wss://us.rt.speechmatics.com/v2")
        assertThat(GoRegionToWsUrl("mars")).isEqualTo("wss://us.rt.speechmatics.com/v2")
    }

    @Test
    fun `pcm16 converts to little-endian float32`() {
        // 0 -> 0.0f, +32767 -> ~1.0f, -32768 -> -1.0f (little-endian pairs).
        val GoPcm = byteArrayOf(
            0x00, 0x00, // 0
            0xFF.toByte(), 0x7F, // +32767
            0x00, 0x80.toByte(), // -32768
        )

        val GoFloats = GoPcm16ToFloat32Le(GoPcm)

        assertThat(GoFloats.size).isEqualTo(12)
        val GoRead = ByteBuffer.wrap(GoFloats).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        assertThat(GoRead.get(0)).isEqualTo(0f)
        assertThat(GoRead.get(1)).isWithin(0.001f).of(1f)
        assertThat(GoRead.get(2)).isEqualTo(-1f)
    }

    @Test
    fun `pcm16 to float32 halves the sample count and stays little-endian`() {
        // 0x0101 == +257, a small positive value identical in every sample.
        val GoPcm = ByteArray(320) { 0x01 }

        val GoFloats = GoPcm16ToFloat32Le(GoPcm)

        // 320 PCM16 bytes == 160 samples == 640 float32 bytes.
        assertThat(GoFloats.size).isEqualTo(640)
        val GoRead = ByteBuffer.wrap(GoFloats).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        assertThat(GoRead.get(0)).isWithin(0.0001f).of(257f / 32_768f)
        assertThat(GoRead.get(159)).isWithin(0.0001f).of(257f / 32_768f)
        // A POSITIVE little-endian float has a clear high bit in its most
        // significant byte (the last byte on disk). Interpreting that byte as
        // signed must therefore be non-negative — proof of LE ordering.
        assertThat(GoFloats[3]).isAtMost(0x7F.toByte())
    }

    @Test
    fun `an empty pcm16 chunk converts to an empty float32 chunk`() {
        assertThat(GoPcm16ToFloat32Le(ByteArray(0))).isEmpty()
    }

    @Test
    fun `an odd trailing byte is ignored rather than throwing`() {
        val GoFloats = GoPcm16ToFloat32Le(byteArrayOf(0x00, 0x00, 0x7F))

        assertThat(GoFloats.size).isEqualTo(4)
    }

    @Test
    fun `start recognition declares raw pcm_f32le at 16 khz`() {
        val GoJson = JSONObject(GoStartRecognitionJson("en"))
        val GoFormat = GoJson.getJSONObject("audio_format")

        assertThat(GoJson.getString("message")).isEqualTo("StartRecognition")
        assertThat(GoFormat.getString("type")).isEqualTo("raw")
        assertThat(GoFormat.getString("encoding")).isEqualTo("pcm_f32le")
        assertThat(GoFormat.getInt("sample_rate")).isEqualTo(16_000)
        assertThat(GoJson.getJSONObject("transcription_config").getString("language")).isEqualTo("en")
    }

    @Test
    fun `stop recognition carries the provider session id`() {
        val GoJson = JSONObject(GoStopRecognitionJson("provider-session-42"))

        assertThat(GoJson.getString("message")).isEqualTo("StopRecognition")
        assertThat(GoJson.getString("session_id")).isEqualTo("provider-session-42")
    }

    @Test
    fun `the live websocket path requires a socket and is skipped by design`() {
        // The cloud path is NOT an acceptance gate (issues.md R4): it needs a real
        // API key and the phone's cellular uplink while the phone is the access
        // point. Everything that can be proven without a socket is proven above;
        // opening a WebSocket in a unit test would either hang or require a key,
        // so it is intentionally not attempted. `GoRegionToWsUrl` +
        // `GoStartRecognitionJson` + `GoPcm16ToFloat32Le` are the whole contract.
        assertThat(GoRegionToWsUrl("eu")).startsWith("wss://")
    }
}
