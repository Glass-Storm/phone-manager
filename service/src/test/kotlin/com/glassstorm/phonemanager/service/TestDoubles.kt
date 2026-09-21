package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.adapter.speech.SttPort
import com.glassstorm.phonemanager.domain.dto.Device
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
    private val GoRows: MutableMap<String, Device> = ConcurrentHashMap()

    override fun GoUpsert(device: Device) {
        GoRows[device.GoDeviceId] = device
    }

    override fun GoGet(deviceId: String): Device? = GoRows[deviceId]

    override fun GoGetByTokenHash(tokenHash: String): Device? = GoRows.values.firstOrNull { it.GoTokenHash == tokenHash }

    override fun GoList(): List<Device> = GoRows.values.sortedBy { it.GoDeviceId }

    override fun GoTouch(
        deviceId: String,
        seenAtMs: Long,
    ) {
        val GoExisting = GoRows[deviceId] ?: return
        GoRows[deviceId] = GoExisting.copy(GoLastSeenMs = seenAtMs)
    }

    override fun GoDelete(deviceId: String) {
        GoRows.remove(deviceId)
    }
}

/**
 * [SttPort] fake that always recognizes the same utterance, counts audio frames,
 * and records every session it was asked to close (so "exactly once" teardown is
 * assertable).
 */
class FakeSttPort(
    private val GoTranscript: String = "hello from fake stt",
) : SttPort {
    private val GoAudioCount = AtomicInteger()

    val GoAudioFrameCount: Int get() = GoAudioCount.get()

    private val GoClosing = Collections.synchronizedList(mutableListOf<String>())

    /** Session ids passed to [GoClose], in arrival order. */
    val GoClosedSessions: List<String> get() = synchronized(GoClosing) { GoClosing.toList() }

    override suspend fun GoTranscribe(
        sessionId: String,
        audioPcm16: ByteArray,
        sampleRateHz: Int,
    ): String? {
        GoAudioCount.incrementAndGet()
        return GoTranscript
    }

    override suspend fun GoClose(sessionId: String) {
        GoClosing += sessionId
    }
}

/** [FrameSink] fake that records every opaque video NAL it is handed. */
class FakeFrameSink : FrameSink {
    private val GoNals = Collections.synchronizedList(mutableListOf<ByteArray>())

    val GoVideoNals: List<ByteArray> get() = synchronized(GoNals) { GoNals.toList() }

    override fun GoAcceptVideo(
        sessionId: String,
        h264Nal: ByteArray,
    ) {
        GoNals += h264Nal
    }
}
