package com.glassstorm.phonemanager.adapter.jvm.ai

import com.glassstorm.phonemanager.core.domain.adapter.speech.SpeechPort
import com.glassstorm.phonemanager.core.model.SpeechUtterance
import java.util.concurrent.ConcurrentHashMap

/**
 * The DEFAULT speech engine for every JVM/unit build: fully offline and
 * DETERMINISTIC, mirroring `MockSttAdapter`'s `mock:<len>:<hash>` style.
 *
 * The pseudo-transcript is a pure function of the PCM bytes, so the same chunk
 * yields the same utterance on any device, in any process, on any run. The
 * speaker is always unresolved (`null`): the fake never invents an enrolled name,
 * which is exactly the hub's contract for an unregistered speaker.
 *
 * Session lifecycle mirrors the STT port: a session opens implicitly on the first
 * chunk and is closed by [close]; a chunk after [close] returns `null` rather than
 * reviving it. [close] is idempotent.
 */
class FakeSpeechPort : SpeechPort {
    private val closedSessions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun transcribe(
        sessionId: String,
        pcm16: ByteArray,
    ): SpeechUtterance? {
        if (sessionId in closedSessions) return null
        if (pcm16.isEmpty()) return null
        return SpeechUtterance(text = "mock:${pcm16.size}:${Fnv1a.hex(pcm16)}", speakerName = null)
    }

    override suspend fun close(sessionId: String) {
        closedSessions.add(sessionId)
    }

    /** Adapter-local observability: whether [sessionId] has been closed. */
    fun isSessionClosed(sessionId: String): Boolean = sessionId in closedSessions
}
