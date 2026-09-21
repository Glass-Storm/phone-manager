package com.glassstorm.phonemanager.testkit

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Kotlin mirror of `tools/mockpeer/frames.go`.
 *
 * The E2E gate is byte-EXACT video passthrough: the hub must hand the recording
 * [com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink] the same bytes the
 * Go peer generated. Re-deriving those bytes in the test (rather than only
 * checking a length) is what makes "the relay did not decode/re-encode" an actual
 * assertion. This function MUST stay byte-identical to `SyntheticVideoNAL`:
 *
 * ```go
 * nal  := []byte{0x00, 0x00, 0x00, 0x01, 0x65}
 * body := make([]byte, 24)
 * for i := range body { body[i] = byte((index*17 + i) % 251) }
 * return append(nal, body...)
 * ```
 */
fun syntheticVideoNal(index: Int): ByteArray {
    val nal = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65)
    val body = ByteArray(24) { i -> ((index * 17 + i) % 251).toByte() }
    return nal + body
}

/**
 * The Kotlin mirror of Go's `SyntheticAudioFrame`: a deterministic little-endian
 * PCM16 mono frame of 20 ms at 16 kHz (640 bytes).
 *
 * ```go
 * buf := make([]byte, 320*2)
 * for i := 0; i < 320; i++ {
 *     sample := int16((index*31 + i*7) % 32767)
 *     binary.LittleEndian.PutUint16(buf[i*2:], uint16(sample))
 * }
 * ```
 *
 * Go computes in `int` and truncates to `int16` on the way in, so the Kotlin side
 * reproduces the same wrap explicitly before writing the little-endian pair.
 */
fun syntheticAudioFrame(index: Int): ByteArray {
    val buf = ByteBuffer.allocate(AUDIO_SAMPLES_PER_FRAME * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until AUDIO_SAMPLES_PER_FRAME) {
        val wide = (index * 31 + i * 7) % 32767
        buf.putShort(wide.toShort())
    }
    return buf.array()
}

/** Mirrors `audioSamplesPerFrame` in `frames.go` (20 ms of mono audio at 16 kHz). */
const val AUDIO_SAMPLES_PER_FRAME: Int = 320

/** Mirrors `audioSampleRateHz` in `frames.go`, the frozen wire contract. */
const val AUDIO_SAMPLE_RATE_HZ: Int = 16_000
