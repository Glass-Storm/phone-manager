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
internal class RelayQueue(
    audioCapacity: Int,
    videoCapacity: Int,
) {
    val GoAudio: Channel<ByteArray> = Channel(capacity = audioCapacity)

    val GoVideo: Channel<ByteArray> = Channel(capacity = videoCapacity)

    /** Park until [pcm] fits. Audio is the irreplaceable half: it is never dropped. */
    suspend fun GoAdmitAudio(pcm: ByteArray) {
        GoAudio.send(pcm)
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
