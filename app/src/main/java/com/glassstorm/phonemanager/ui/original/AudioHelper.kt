package com.glassstorm.phonemanager.ui.original

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object AudioHelper {
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isRecording = false
    private var audioTrack: AudioTrack? = null

    /**
     * Records audio continuously until stopRecording() is called,
     * or until maxDurationSec (safety ceiling of 120 seconds).
     */
    suspend fun recordAudioClip(maxDurationSec: Int = 120): Pair<ByteArray, Int> =
        withContext(Dispatchers.IO) {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
            if (bufferSize <= 0) return@withContext Pair(ByteArray(0), 0)

            val record =
                try {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG_IN,
                        AUDIO_FORMAT,
                        bufferSize * 2,
                    )
                } catch (e: SecurityException) {
                    return@withContext Pair(ByteArray(0), 0)
                } catch (e: Exception) {
                    return@withContext Pair(ByteArray(0), 0)
                }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return@withContext Pair(ByteArray(0), 0)
            }

            audioRecord = record
            isRecording = true
            record.startRecording()

            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(bufferSize)
            val startTime = System.currentTimeMillis()
            val maxDurationMs = maxDurationSec * 1000L

            try {
                while (isRecording && (System.currentTimeMillis() - startTime) < maxDurationMs) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        outputStream.write(buffer, 0, read)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    record.stop()
                    record.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                audioRecord = null
                isRecording = false
            }

            val totalDurationSec = (((System.currentTimeMillis() - startTime) / 1000L).coerceAtLeast(1L)).toInt()
            Pair(outputStream.toByteArray(), totalDurationSec)
        }

    fun stopRecording() {
        isRecording = false
    }

    suspend fun playAudioClip(audioData: ByteArray) =
        withContext(Dispatchers.IO) {
            if (audioData.isEmpty()) return@withContext

            try {
                audioTrack?.release()
                val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
                val track =
                    AudioTrack
                        .Builder()
                        .setAudioAttributes(
                            AudioAttributes
                                .Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        ).setAudioFormat(
                            AudioFormat
                                .Builder()
                                .setEncoding(AUDIO_FORMAT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(CHANNEL_CONFIG_OUT)
                                .build(),
                        ).setBufferSizeInBytes(minBufSize.coerceAtLeast(audioData.size))
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()

                track.write(audioData, 0, audioData.size)
                track.play()
                audioTrack = track
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
}
