package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.adapter.speech.SttPort
import com.glassstorm.phonemanager.domain.dto.Device

/**
 * Test-local fakes for the domain ports. They deliberately implement the domain
 * INTERFACES only, exactly like a real adapter would — `:service` has no build
 * edge to `:adapter`, so a concrete adapter class is unreachable here by design.
 */

/** In-memory [DeviceRepository] with the same upsert-by-id semantics as SQLite. */
class FakeDeviceRepository : DeviceRepository {
    private val GoRows: MutableMap<String, Device> = mutableMapOf()

    override fun GoUpsert(device: Device) {
        GoRows[device.GoDeviceId] = device
    }

    override fun GoGet(deviceId: String): Device? = GoRows[deviceId]

    override fun GoGetByTokenHash(tokenHash: String): Device? =
        GoRows.values.firstOrNull { it.GoTokenHash == tokenHash }

    override fun GoList(): List<Device> = GoRows.values.sortedBy { it.GoDeviceId }

    override fun GoTouch(deviceId: String, seenAtMs: Long) {
        val GoExisting = GoRows[deviceId] ?: return
        GoRows[deviceId] = GoExisting.copy(GoLastSeenMs = seenAtMs)
    }

    override fun GoDelete(deviceId: String) {
        GoRows.remove(deviceId)
    }
}

/** [SttPort] fake that always recognizes the same utterance and counts audio frames. */
class FakeSttPort(private val GoTranscript: String = "hello from fake stt") : SttPort {
    var GoAudioFrameCount: Int = 0
        private set

    override suspend fun GoTranscribe(audioPcm16: ByteArray, sampleRateHz: Int): String? {
        GoAudioFrameCount += 1
        return GoTranscript
    }
}

/** [FrameSink] fake that records every opaque video NAL it is handed. */
class FakeFrameSink : FrameSink {
    val GoVideoNals: MutableList<ByteArray> = mutableListOf()

    override fun GoAcceptVideo(sessionId: String, h264Nal: ByteArray) {
        GoVideoNals += h264Nal
    }
}
