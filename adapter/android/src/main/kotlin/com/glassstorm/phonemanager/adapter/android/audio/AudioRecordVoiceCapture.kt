package com.glassstorm.phonemanager.adapter.android.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.glassstorm.phonemanager.core.domain.adapter.enrollment.VoiceCapturePort
import com.glassstorm.phonemanager.core.model.AudioFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import android.media.AudioFormat as AndroidAudioFormat

/**
 * [VoiceCapturePort] backed by `AudioRecord`, used only by the on-device voiceprint
 * enrollment flow.
 *
 * Capture runs on [Dispatchers.IO] because an 8 s read blocks its thread. The
 * source is `VOICE_RECOGNITION` (no AGC/noise suppression) and the format is the
 * hub's canonical PCM16 mono 16 kHz, so the bytes go to `EnrollmentStore.enroll`
 * unchanged.
 *
 * A missing `RECORD_AUDIO` grant is NOT an exception: this adapter returns `null`,
 * matching the port's contract, and the screen renders the "microphone
 * unavailable" state instead of crashing.
 */
class AudioRecordVoiceCapture(
    private val context: Context,
) : VoiceCapturePort {
    override suspend fun record(durationMillis: Long): ByteArray? =
        withContext(Dispatchers.IO) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return@withContext null
            }

            val targetBytes = bytesFor(durationMillis)
            val minBuffer = minBufferBytes()
            if (targetBytes <= 0 || minBuffer <= 0) return@withContext null

            val recorder =
                runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        AudioFormat.SAMPLE_RATE_HZ,
                        AndroidAudioFormat.CHANNEL_IN_MONO,
                        AndroidAudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuffer, CHUNK_BYTES * BUFFER_CHUNKS),
                    )
                }.getOrNull() ?: return@withContext null

            try {
                if (recorder.state != AudioRecord.STATE_INITIALIZED) return@withContext null
                recorder.startRecording()
                val captured = ByteArrayOutputStream(targetBytes)
                val chunk = ByteArray(CHUNK_BYTES)
                while (captured.size() < targetBytes) {
                    val read = recorder.read(chunk, 0, chunk.size)
                    if (read <= 0) break
                    captured.write(chunk, 0, read)
                }
                captured.toByteArray()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                null
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }

    /** Bytes needed for [durationMillis] at the canonical format. */
    private fun bytesFor(durationMillis: Long): Int =
        (AudioFormat.SAMPLE_RATE_HZ * AudioFormat.BYTES_PER_SAMPLE * durationMillis / 1000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private fun minBufferBytes(): Int =
        AudioRecord.getMinBufferSize(
            AudioFormat.SAMPLE_RATE_HZ,
            AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )

    private companion object {
        /** One 20 ms frame, the hub's canonical chunk size. */
        const val CHUNK_BYTES: Int = 320 * 2

        /** Ring-buffer headroom, in chunks, when the device's minimum is smaller. */
        const val BUFFER_CHUNKS: Int = 4
    }
}
