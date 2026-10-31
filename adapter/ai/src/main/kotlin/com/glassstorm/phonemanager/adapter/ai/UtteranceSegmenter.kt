package com.glassstorm.phonemanager.adapter.ai

import com.glassstorm.phonemanager.core.model.AudioFormat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * One utterance the [UtteranceSegmenter] has decided is complete.
 *
 * [pcm16] is the WHOLE buffered utterance (speech plus the trailing silence that
 * ended it), ready to be handed to the ASR engine in a single call. [startMs] is
 * the utterance's onset on the session timeline (milliseconds since the first chunk
 * the segmenter saw for the session); [endMs] is the position of the chunk that
 * closed it.
 */
class ClosedUtterance(
    val pcm16: ByteArray,
    val startMs: Long,
    val endMs: Long,
)

/**
 * A pure, engine-free utterance segmenter for the relay's 20 ms PCM16 chunk stream.
 *
 * The relay hands the speech port one chunk at a time, so transcribing a chunk in
 * isolation can never see a complete utterance. This helper accumulates chunks per
 * [sessionId] until a simple energy VAD decides the utterance has ended:
 *
 *  1. every non-empty chunk is appended to the session buffer;
 *  2. the chunk RMS (normalised PCM16 in `[-1, 1]`) is compared with
 *     [speechRmsThreshold] to classify the chunk as speech or silence;
 *  3. the first speech chunk opens the utterance and records its onset; speech
 *     resets the silence counter;
 *  4. silence after speech increments the counter, and once silence has lasted
 *     [silenceMillis] the utterance closes;
 *  5. an utterance that grows past [maxUtteranceMillis] closes anyway, bounding
 *     memory and ASR latency. Only audio from the onset onward counts toward that
 *     cap: silence BEFORE any speech is retained as a small pre-roll bounded by
 *     [preRollMillis], so a long silent lead-in can neither force a premature close
 *     nor grow the buffer without bound.
 *
 * The class holds no Android, engine, or coroutine types: it is a deterministic
 * state machine that is unit-tested on a bare JVM.
 *
 * @param sampleRateHz the PCM16 sample rate of every chunk (the hub's 16 kHz).
 * @param speechRmsThreshold chunk RMS at or above which the chunk counts as speech.
 * @param silenceMillis trailing silence that closes an open utterance.
 * @param maxUtteranceMillis maximum buffered utterance length before a forced close.
 * @param preRollMillis maximum pre-speech silence retained ahead of the onset.
 */
class UtteranceSegmenter(
    private val sampleRateHz: Int = AudioFormat.SAMPLE_RATE_HZ,
    private val speechRmsThreshold: Float = DEFAULT_SPEECH_RMS_THRESHOLD,
    private val silenceMillis: Long = DEFAULT_SILENCE_MILLIS,
    private val maxUtteranceMillis: Long = DEFAULT_MAX_UTTERANCE_MILLIS,
    private val preRollMillis: Long = DEFAULT_PRE_ROLL_MILLIS,
) {
    private val maxBufferBytes: Int =
        (sampleRateHz.toLong() * maxUtteranceMillis / 1000L * AudioFormat.BYTES_PER_SAMPLE).toInt()

    private val maxPreRollBytes: Int =
        (sampleRateHz.toLong() * preRollMillis / 1000L * AudioFormat.BYTES_PER_SAMPLE).toInt()

    private val sessions = ConcurrentHashMap<String, Session>()

    /**
     * Feeds one chunk for [sessionId].
     *
     * Returns the [ClosedUtterance] when this chunk completed an utterance, or `null`
     * while the utterance is still open (or when the chunk carried only silence).
     */
    fun accept(
        sessionId: String,
        pcm16: ByteArray,
    ): ClosedUtterance? {
        if (pcm16.isEmpty()) return null
        val session = sessions.computeIfAbsent(sessionId) { Session() }
        synchronized(session) {
            return acceptLocked(session, pcm16)
        }
    }

    /** Drops the buffered state for [sessionId]. Idempotent. */
    fun close(sessionId: String) {
        sessions.remove(sessionId)
    }

    /**
     * Closes [sessionId]'s buffered utterance immediately, regardless of trailing
     * silence, and returns it for transcription. Returns `null` when the session
     * had no speech buffered. The session is removed either way.
     */
    fun flush(sessionId: String): ClosedUtterance? {
        val session = sessions.remove(sessionId) ?: return null
        synchronized(session) {
            if (!session.speechSeen || session.buffer.size() == 0) return null
            return ClosedUtterance(
                pcm16 = session.buffer.toByteArray(),
                startMs = session.startMs,
                endMs = millisFor(session.consumedSamples),
            )
        }
    }

    private fun acceptLocked(
        session: Session,
        pcm16: ByteArray,
    ): ClosedUtterance? {
        val sampleCount = pcm16.size / AudioFormat.BYTES_PER_SAMPLE
        val chunkStartMs = millisFor(session.consumedSamples)
        session.consumedSamples += sampleCount
        val chunkEndMs = millisFor(session.consumedSamples)
        val chunkMillis = (chunkEndMs - chunkStartMs).coerceAtLeast(1L)

        if (rms(pcm16) >= speechRmsThreshold) {
            if (!session.speechSeen) {
                session.speechSeen = true
                session.startMs = chunkStartMs
                session.flushPreRollIntoBuffer()
            }
            session.buffer.write(pcm16)
            session.speechBytes += pcm16.size
            session.silenceMillis = 0L
        } else if (session.speechSeen) {
            session.buffer.write(pcm16)
            session.speechBytes += pcm16.size
            session.silenceMillis += chunkMillis
        } else {
            session.retainPreRoll(pcm16, maxPreRollBytes)
        }

        return when {
            session.speechSeen && session.speechBytes >= maxBufferBytes ->
                closeUtterance(session, chunkEndMs)
            session.speechSeen && session.silenceMillis >= silenceMillis ->
                closeUtterance(session, chunkEndMs)
            else -> null
        }
    }

    private fun closeUtterance(
        session: Session,
        endMs: Long,
    ): ClosedUtterance {
        val utterance =
            ClosedUtterance(
                pcm16 = session.buffer.toByteArray(),
                startMs = session.startMs,
                endMs = endMs,
            )
        session.resetBuffer()
        return utterance
    }

    private fun millisFor(samples: Long): Long = samples * 1000L / sampleRateHz

    private fun rms(pcm16: ByteArray): Float {
        val samples = AudioPcm.toFloats(pcm16)
        if (samples.isEmpty()) return 0f
        var sumOfSquares = 0.0
        for (sample in samples) {
            sumOfSquares += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sumOfSquares / samples.size).toFloat()
    }

    private class Session {
        val buffer = ByteArrayOutputStream()

        /** Total samples consumed for the session, used to timestamp onsets. */
        var consumedSamples: Long = 0L

        var speechSeen: Boolean = false

        var silenceMillis: Long = 0L

        var startMs: Long = 0L

        /** Bytes admitted since the onset; the max-utterance cap applies to this only. */
        var speechBytes: Long = 0L

        private val preRoll = ArrayDeque<ByteArray>()

        private var preRollBytes: Int = 0

        fun retainPreRoll(
            chunk: ByteArray,
            limitBytes: Int,
        ) {
            preRoll.addLast(chunk)
            preRollBytes += chunk.size
            while (preRollBytes > limitBytes && preRoll.isNotEmpty()) {
                preRollBytes -= preRoll.removeFirst().size
            }
        }

        fun flushPreRollIntoBuffer() {
            for (chunk in preRoll) {
                buffer.write(chunk)
            }
            preRoll.clear()
            preRollBytes = 0
        }

        fun resetBuffer() {
            buffer.reset()
            preRoll.clear()
            preRollBytes = 0
            speechSeen = false
            silenceMillis = 0L
            startMs = 0L
            speechBytes = 0L
        }
    }

    companion object {
        /** Chunk RMS at or above which audio counts as speech (normalised PCM16). */
        const val DEFAULT_SPEECH_RMS_THRESHOLD: Float = 0.01f

        /** Trailing silence that closes an utterance: 400 ms. */
        const val DEFAULT_SILENCE_MILLIS: Long = 400L

        /** Maximum buffered utterance length: 10 s. */
        const val DEFAULT_MAX_UTTERANCE_MILLIS: Long = 10_000L

        /** Pre-speech silence retained ahead of the onset: 200 ms. */
        const val DEFAULT_PRE_ROLL_MILLIS: Long = 200L
    }
}
