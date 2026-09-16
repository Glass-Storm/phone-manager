package com.glassstorm.phonemanager.ui

import com.glassstorm.phonemanager.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.domain.dto.Device
import com.glassstorm.phonemanager.domain.dto.HotspotInfo
import com.glassstorm.phonemanager.domain.dto.PairOutcome
import com.glassstorm.phonemanager.domain.dto.Pairing
import com.glassstorm.phonemanager.domain.dto.RelayResult
import com.glassstorm.phonemanager.domain.dto.RelaySession
import com.glassstorm.phonemanager.domain.dto.RelayStats
import com.glassstorm.phonemanager.domain.network.HotspotFailure
import com.glassstorm.phonemanager.domain.network.HotspotUnavailableException
import com.glassstorm.phonemanager.domain.service.PairingService
import com.glassstorm.phonemanager.domain.service.StreamService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Test-local fakes for the UI tests.
 *
 * They implement the DOMAIN interfaces only, exactly like a real adapter would.
 * `:app`'s test sources must never import a concrete `:adapter` class: the UI is
 * only allowed to know the ports, so binding a fake under the port type is the
 * strongest possible check that the screens never reach past the interface.
 */

/** [HubServer] fake: `GoStart` always binds [GoBoundPortValue] and flips running. */
class FakeHubServer(private val GoBoundPortValue: Int = 40404) : HubServer {
    var GoStartCalls: Int = 0
        private set

    private var GoRunning: Boolean = false

    override fun GoStart(port: Int) {
        GoStartCalls += 1
        GoRunning = true
    }

    override fun GoStop() {
        GoRunning = false
    }

    override fun GoIsRunning(): Boolean = GoRunning

    override fun GoBoundPort(): Int = if (GoRunning) GoBoundPortValue else 0
}

/**
 * [HotspotController] fake.
 *
 * [GoFailWith] makes `GoStartHotspot` throw the real typed exception, so the
 * "could not start" path is exercised exactly as production would raise it.
 */
class FakeHotspotController(
    private val GoInfo: HotspotInfo = HotspotInfo(
        GoSsid = "EcoSys-Phone",
        GoPassphrase = "hunter2-phone",
        GoGatewayIp = "192.168.43.1",
    ),
    private val GoFailWith: HotspotFailure? = null,
) : HotspotController {
    private var GoActive: Boolean = false

    var GoStartCalls: Int = 0
        private set

    override fun GoStartHotspot(): HotspotInfo {
        GoStartCalls += 1
        GoFailWith?.let { throw HotspotUnavailableException(it) }
        GoActive = true
        return GoInfo
    }

    override fun GoStopHotspot() {
        GoActive = false
    }

    override fun GoIsActive(): Boolean = GoActive

    override fun GoDetectManualTether(): HotspotInfo? = if (GoActive) GoInfo else null
}

/**
 * [PairingService] fake with the same one-window-at-a-time semantics as the real
 * service: [GoOpenWindow] REPLACES the previous window and returns the next PIN in
 * [GoPinSequence], so a UI that rendered a stale PIN would fail the two-open test.
 */
class FakePairingService(
    private val GoPinSequence: List<String> = listOf("428193", "999999"),
    private val GoClock: () -> Long = { 1_000_000L },
    GoSeed: List<Device> = emptyList(),
) : PairingService {
    private val GoRows: MutableMap<String, Device> = linkedMapOf()

    private var GoWindow: Pairing? = null

    var GoOpenCalls: Int = 0
        private set

    init {
        GoSeed.forEach { GoRows[it.GoDeviceId] = it }
    }

    fun GoSeedDevice(
        deviceId: String,
        deviceName: String,
        role: String = "GLASS",
    ) {
        GoRows[deviceId] = Device(
            GoDeviceId = deviceId,
            GoDeviceName = deviceName,
            GoRole = role,
            GoTokenHash = "hash-$deviceId",
            GoPairedAtMs = GoClock(),
            GoLastSeenMs = null,
        )
    }

    override fun GoOpenWindow(ttlMs: Long): Pairing {
        val GoIndex = GoOpenCalls.coerceAtMost(GoPinSequence.lastIndex)
        GoOpenCalls += 1
        val GoFresh = Pairing(
            GoPin = GoPinSequence[GoIndex],
            GoExpiresAtMs = GoClock() + ttlMs,
        )
        GoWindow = GoFresh
        return GoFresh
    }

    override fun GoStopWindow() {
        GoWindow = null
    }

    override fun GoPair(pin: String, deviceName: String, role: String): PairOutcome =
        PairOutcome.GoRejected(GoReason = PairOutcome.GoReasonPinInvalid)

    override fun GoVerifyToken(token: String): Device? = null

    override fun GoTouchLastSeen(deviceId: String, seenAtMs: Long) = Unit

    override fun GoRevoke(deviceId: String) {
        GoRows.remove(deviceId)
    }

    override fun GoListPaired(): List<Device> = GoRows.values.toList()
}

/**
 * [StreamService] fake.
 *
 * It implements the DOMAIN port exactly like `StreamServiceImpl` would, and mints
 * a DISTINCT session id per [GoOpenSession] so a screen rendering a stale session
 * cannot pass. Counters come from [GoNextStats], which the test pushes through
 * [GoReportStats] to simulate new frames arriving on the peer between polls — the
 * screen has no other way to learn them, so a refresh loop that never runs leaves
 * the counters at zero and fails the test.
 *
 * Results are published on a [MutableSharedFlow] with `replay = 1`: the fake's
 * `GoResults` is collected by the ViewModel while the session is live, and the
 * latest utterance is what the screen renders with its speaker label.
 */
class FakeStreamService(
    private val GoSessionIds: List<String> = listOf("s-1", "s-2", "s-3"),
) : StreamService {
    private val GoResultsFlow = MutableSharedFlow<RelayResult>(replay = 1)

    private var GoStatsValue = RelayStats(
        GoAudioFrames = 0L,
        GoVideoFrames = 0L,
        GoVideoDropped = 0L,
        GoTranscripts = 0L,
        GoLiveSessions = 0,
    )

    private var GoOpenCalls: Int = 0
    private var GoClosed: MutableList<String> = mutableListOf()

    /** How many sessions the fake currently believes are live. */
    var GoLiveSessions: Int = 0
        private set

    fun GoOpenCount(): Int = GoOpenCalls

    fun GoClosedIds(): List<String> = GoClosed.toList()

    /** Replaces the snapshot [GoStats] returns, as if the peer had pushed more media. */
    fun GoReportStats(
        GoAudioFrames: Long,
        GoVideoFrames: Long,
        GoVideoDropped: Long,
        GoTranscripts: Long,
    ) {
        GoStatsValue = RelayStats(
            GoAudioFrames = GoAudioFrames,
            GoVideoFrames = GoVideoFrames,
            GoVideoDropped = GoVideoDropped,
            GoTranscripts = GoTranscripts,
            GoLiveSessions = GoLiveSessions,
        )
    }

    /** Publish a recognized utterance, as the STT engine would mid-stream. */
    fun GoEmitTranscript(GoText: String, GoSpeakerLabel: String = "Speaker 1") {
        GoResultsFlow.tryEmit(
            RelayResult(GoText = GoText, GoSpeakerLabel = GoSpeakerLabel, GoPtsMs = 0L),
        )
    }

    override fun GoOpenSession(deviceId: String): RelaySession {
        val GoIndex = GoOpenCalls.coerceAtMost(GoSessionIds.lastIndex)
        GoOpenCalls += 1
        GoLiveSessions += 1
        return RelaySession(GoSessionId = GoSessionIds[GoIndex], GoDeviceId = deviceId)
    }

    override suspend fun GoPushAudio(sessionId: String, audioPcm16: ByteArray, sampleRateHz: Int) = Unit

    override fun GoPushVideo(sessionId: String, h264Nal: ByteArray) = Unit

    override fun GoResults(sessionId: String): Flow<RelayResult> =
        if (GoLiveSessions > 0) GoResultsFlow else emptyFlow()

    override suspend fun GoCloseSession(sessionId: String) {
        GoClosed.add(sessionId)
        GoLiveSessions = (GoLiveSessions - 1).coerceAtLeast(0)
    }

    override fun GoStats(): RelayStats = GoStatsValue.copy(GoLiveSessions = GoLiveSessions)
}
