package com.glassstorm.phonemanager.transport.grpc

import com.glassstorm.phonemanager.core.domain.adapter.enrollment.EnrollmentStore
import com.glassstorm.phonemanager.core.domain.adapter.gesture.GesturePort
import com.glassstorm.phonemanager.core.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.domain.adapter.speech.SpeechPort
import com.glassstorm.phonemanager.core.domain.adapter.speech.TtsPort
import com.glassstorm.phonemanager.core.model.Device
import com.glassstorm.phonemanager.core.model.EnrollOutcome
import com.glassstorm.phonemanager.core.model.EnrolledSpeaker
import com.glassstorm.phonemanager.core.model.GestureOutcome
import com.glassstorm.phonemanager.core.model.SpeechUtterance
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/*
 * Test-local fakes for the domain ports. They deliberately implement the domain
 * INTERFACES only, exactly like a real adapter would — `:service` has no build
 * edge to `:adapter`, so a concrete adapter class is unreachable here by design.
 */

/**
 * In-memory [DeviceRepository] with the same upsert-by-id semantics as SQLite.
 *
 * Backed by [ConcurrentHashMap] so the concurrency suite can drive it from many
 * threads without the map itself being the thing under test.
 */
class FakeDeviceRepository : DeviceRepository {
    private val rows: MutableMap<String, Device> = ConcurrentHashMap()

    override fun upsert(device: Device) {
        rows[device.deviceId] = device
    }

    override fun get(deviceId: String): Device? = rows[deviceId]

    override fun getByTokenHash(tokenHash: String): Device? = rows.values.firstOrNull { it.tokenHash == tokenHash }

    override fun list(): List<Device> = rows.values.sortedBy { it.deviceId }

    override fun touch(
        deviceId: String,
        seenAtMs: Long,
    ) {
        val existing = rows[deviceId] ?: return
        rows[deviceId] = existing.copy(lastSeenMs = seenAtMs)
    }

    override fun delete(deviceId: String) {
        rows.remove(deviceId)
    }
}

/**
 * [SpeechPort] fake that always recognizes the same utterance, counts audio frames,
 * and records every session it was asked to close (so "exactly once" teardown is
 * assertable).
 */
class FakeSpeechPort(
    private val transcript: String = "hello from fake speech",
    private val speakerName: String? = null,
) : SpeechPort {
    private val audioCount = AtomicInteger()

    val audioFrameCount: Int get() = audioCount.get()

    private val closing = Collections.synchronizedList(mutableListOf<String>())

    /** Session ids passed to [close], in arrival order. */
    val closedSessions: List<String> get() = synchronized(closing) { closing.toList() }

    override suspend fun transcribe(
        sessionId: String,
        pcm16: ByteArray,
    ): SpeechUtterance? {
        audioCount.incrementAndGet()
        return SpeechUtterance(text = transcript, speakerName = speakerName)
    }

    override suspend fun close(sessionId: String) {
        closing += sessionId
    }
}

/** [GesturePort] fake: a fixed outcome for a non-empty still, `null` for an empty one. */
class FakeGesturePort(
    private val outcome: GestureOutcome = GestureOutcome(label = "Thumb_Up", text = "Yes", confidence = 0.5f),
) : GesturePort {
    override suspend fun recognize(jpeg: ByteArray): GestureOutcome? = if (jpeg.isEmpty()) null else outcome
}

/** [TtsPort] fake: fixed PCM bytes for non-empty text, an empty array otherwise. */
class FakeTtsPort(
    private val pcm16: ByteArray = ByteArray(4) { 1 },
) : TtsPort {
    override suspend fun synthesize(text: String): ByteArray = if (text.isEmpty()) ByteArray(0) else pcm16
}

/** [EnrollmentStore] fake accepting every request. */
class FakeEnrollmentStore : EnrollmentStore {
    override suspend fun enroll(
        name: String,
        pcm16: ByteArray,
    ): EnrollOutcome = EnrollOutcome.accepted

    override suspend fun names(): List<String> = emptyList()

    override suspend fun enrollments(): List<EnrolledSpeaker> = emptyList()
}

/** [FrameSink] fake that records every opaque video NAL it is handed. */
class FakeFrameSink : FrameSink {
    private val nals = Collections.synchronizedList(mutableListOf<ByteArray>())

    val videoNals: List<ByteArray> get() = synchronized(nals) { nals.toList() }

    override fun acceptVideo(
        sessionId: String,
        h264Nal: ByteArray,
    ) {
        nals += h264Nal
    }
}
