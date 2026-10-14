package com.glassstorm.phonemanager.core.service

import kotlinx.coroutines.channels.Channel

/** One queued JPEG still plus the capture timestamp its gesture output inherits. */
internal data class QueuedImage(
    val jpeg: ByteArray,
    val ptsMs: Long,
)

/**
 * Per-session media queue for one relay session.
 *
 * ## Why audio and video are separate channels
 *
 * Audio and video have OPPOSITE backpressure policies and a single shared channel
 * cannot express both: evicting the "oldest" entry of a mixed queue would sometimes
 * evict AUDIO, which is never allowed. So each media kind gets its own bounded
 * channel with its own admission rule:
 *
 *  * [audio] — SUSPEND. [admitAudio] parks until the buffer has room, so audio
 *    is NEVER dropped.
 *  * [video] — drop-oldest. [admitVideo] evicts the oldest queued frame when
 *    the buffer is full, so the live edge keeps flowing.
 *  * [image] — SUSPEND. [admitImage] parks until the buffer has room, so a still
 *    is never dropped without a counter (a mirrored 10 fps source is naturally
 *    rate-limited by recognition latency).
 */
internal class RelayQueue(
    audioCapacity: Int,
    videoCapacity: Int,
    imageCapacity: Int,
) {
    val audio: Channel<ByteArray> = Channel(capacity = audioCapacity)

    val video: Channel<ByteArray> = Channel(capacity = videoCapacity)

    val image: Channel<QueuedImage> = Channel(capacity = imageCapacity)

    /** Park until [pcm] fits. Audio is the irreplaceable half: it is never dropped. */
    suspend fun admitAudio(pcm: ByteArray) {
        audio.send(pcm)
    }

    /** Park until [image] fits. A still is never dropped silently. */
    suspend fun admitImage(image: QueuedImage) {
        this.image.send(image)
    }

    /**
     * Admit [nal], evicting the oldest queued frame when the buffer is full.
     *
     * Returns `true` only when an eviction was performed — [nal] won a full
     * buffer, the oldest queued frame was taken out, and [nal] was re-offered.
     * The re-offer is best-effort: a concurrent producer can refill the freed
     * slot first, in which case [nal] is dropped while this still returns `true`.
     *
     * Returns `false` when no eviction happened: either [nal] fitted on the first
     * attempt, or the buffer was full but a concurrent consumer drained it before
     * the eviction could run (so [nal] may itself be dropped). This method is the
     * queue's drop-oldest policy, not an "admitted" verdict — callers that count
     * frames count them as OFFERED, never as admitted.
     */
    fun admitVideo(nal: ByteArray): Boolean {
        if (video.trySend(nal).isSuccess) return false
        if (video.tryReceive().isFailure) return false
        video.trySend(nal)
        return true
    }

    /** Close every side so a pump's `for (x in channel)` finishes after draining. */
    fun close() {
        audio.close()
        video.close()
        image.close()
    }
}
