package com.glassstorm.phonemanager.service

import kotlinx.coroutines.channels.Channel

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
 *  * [GoAudio] — SUSPEND. [GoAdmitAudio] parks until the buffer has room, so audio
 *    is NEVER dropped.
 *  * [GoVideo] — drop-oldest. [GoAdmitVideo] evicts the oldest queued frame when
 *    the buffer is full, so the live edge keeps flowing.
 */
internal class RelayQueue(audioCapacity: Int, videoCapacity: Int) {

    val GoAudio: Channel<ByteArray> = Channel(capacity = audioCapacity)

    val GoVideo: Channel<ByteArray> = Channel(capacity = videoCapacity)

    /** Park until [pcm] fits. Audio is the irreplaceable half: it is never dropped. */
    suspend fun GoAdmitAudio(pcm: ByteArray) {
        GoAudio.send(pcm)
    }

    /**
     * Admit [nal], evicting the oldest queued frame when the buffer is full.
     *
     * Returns `true` only when an eviction actually made room (a queued frame was
     * dropped), and `false` when the frame simply fitted.
     */
    fun GoAdmitVideo(nal: ByteArray): Boolean {
        if (GoVideo.trySend(nal).isSuccess) return false
        if (GoVideo.tryReceive().isFailure) return false
        GoVideo.trySend(nal)
        return true
    }

    /** Close both sides so a pump's `for (x in channel)` finishes after draining. */
    fun GoClose() {
        GoAudio.close()
        GoVideo.close()
    }
}
