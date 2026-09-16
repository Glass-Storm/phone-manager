package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.domain.adapter.relay.FrameSink
import com.glassstorm.phonemanager.domain.adapter.speech.SttPort
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContext
import com.glassstorm.phonemanager.domain.dto.RelayResult
import com.glassstorm.phonemanager.domain.dto.RelaySession
import com.glassstorm.phonemanager.domain.service.StreamService
import java.security.SecureRandom

/**
 * Audio/video relay use-cases.
 *
 * Resolves its [SttPort] and [FrameSink] collaborators from the Context registry
 * by their domain INTERFACE types, so any engine (offline mock, cloud provider,
 * recorder) can be composed in without `:service` knowing about it.
 *
 * ## What this layer does NOT do (T14 owns it)
 *
 * This is the correct-and-simple core: it accepts opaque payloads, hands audio to
 * STT, hands video to the sink unchanged, and answers with a result when one is
 * produced. Backpressure, drop-oldest video policy, and mid-stream disconnect
 * cleanup are T14's hardening pass.
 */
class StreamServiceImpl(private val GoCtx: Context) : StreamService {

    private val GoRandom = SecureRandom()
    private val GoSessions: MutableMap<String, RelaySession> = mutableMapOf()

    override fun GoOpenSession(deviceId: String): RelaySession {
        val GoSession = RelaySession(GoSessionId = GoNewSessionId(), GoDeviceId = deviceId)
        GoSessions[GoSession.GoSessionId] = GoSession
        return GoSession
    }

    override suspend fun GoPushAudio(sessionId: String, audioPcm16: ByteArray, sampleRateHz: Int): RelayResult? {
        // An unknown session is dropped rather than resurrected: a frame must never
        // outlive the stream that opened it.
        if (sessionId !in GoSessions) return null
        val GoText = FromContext<SttPort>(GoCtx).GoTranscribe(audioPcm16, sampleRateHz) ?: return null
        return RelayResult(GoText = GoText, GoSpeakerLabel = "", GoPtsMs = 0L)
    }

    override fun GoPushVideo(sessionId: String, h264Nal: ByteArray) {
        if (sessionId !in GoSessions) return
        // Opaque by contract: the exact bytes are handed on, never decoded.
        FromContext<FrameSink>(GoCtx).GoAcceptVideo(sessionId, h264Nal)
    }

    override fun GoCloseSession(sessionId: String) {
        GoSessions.remove(sessionId)
    }

    private fun GoNewSessionId(): String {
        val GoBytes = ByteArray(16).also { GoRandom.nextBytes(it) }
        return GoBytes.joinToString("") { "%02x".format(it) }
    }
}
