package com.glassstorm.phonemanager.ui

import com.glassstorm.phonemanager.battery.BatteryExemption
import com.glassstorm.phonemanager.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.domain.dto.Device
import com.glassstorm.phonemanager.domain.dto.HotspotInfo
import com.glassstorm.phonemanager.domain.dto.HotspotMode
import com.glassstorm.phonemanager.domain.dto.PairOutcome
import com.glassstorm.phonemanager.domain.dto.Pairing
import com.glassstorm.phonemanager.domain.dto.RelayResult
import com.glassstorm.phonemanager.domain.dto.RelaySession
import com.glassstorm.phonemanager.domain.dto.RelayStats
import com.glassstorm.phonemanager.domain.dto.SttEngine
import com.glassstorm.phonemanager.domain.network.HotspotFailure
import com.glassstorm.phonemanager.domain.network.HotspotUnavailableException
import com.glassstorm.phonemanager.domain.service.PairingService
import com.glassstorm.phonemanager.domain.service.StreamService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/*
 * Test-local fakes for the UI tests.
 *
 * They implement the DOMAIN interfaces only, exactly like a real adapter would.
 * `:app`'s test sources must never import a concrete `:adapter` class: the UI is
 * only allowed to know the ports, so binding a fake under the port type is the
 * strongest possible check that the screens never reach past the interface.
 */

/** [HubServer] fake: `GoStart` always binds [GoBoundPortValue] and flips running. */
class FakeHubServer(
    private val GoBoundPortValue: Int = 40404,
) : HubServer {
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
    private val GoInfo: HotspotInfo =
        HotspotInfo(
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
        GoRows[deviceId] =
            Device(
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
        val GoFresh =
            Pairing(
                GoPin = GoPinSequence[GoIndex],
                GoExpiresAtMs = GoClock() + ttlMs,
            )
        GoWindow = GoFresh
        return GoFresh
    }

    override fun GoStopWindow() {
        GoWindow = null
    }

    override fun GoPair(
        pin: String,
        deviceName: String,
        role: String,
    ): PairOutcome = PairOutcome.GoRejected(GoReason = PairOutcome.GoReasonPinInvalid)

    override fun GoVerifyToken(token: String): Device? = null

    override fun GoTouchLastSeen(
        deviceId: String,
        seenAtMs: Long,
    ) = Unit

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

    private var GoStatsValue =
        RelayStats(
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
        GoStatsValue =
            RelayStats(
                GoAudioFrames = GoAudioFrames,
                GoVideoFrames = GoVideoFrames,
                GoVideoDropped = GoVideoDropped,
                GoTranscripts = GoTranscripts,
                GoLiveSessions = GoLiveSessions,
            )
    }

    /** Publish a recognized utterance, as the STT engine would mid-stream. */
    fun GoEmitTranscript(
        GoText: String,
        GoSpeakerLabel: String = "Speaker 1",
    ) {
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

    override suspend fun GoPushAudio(
        sessionId: String,
        audioPcm16: ByteArray,
        sampleRateHz: Int,
    ) = Unit

    override fun GoPushVideo(
        sessionId: String,
        h264Nal: ByteArray,
    ) = Unit

    override fun GoResults(sessionId: String): Flow<RelayResult> = if (GoLiveSessions > 0) GoResultsFlow else emptyFlow()

    override suspend fun GoCloseSession(sessionId: String) {
        GoClosed.add(sessionId)
        GoLiveSessions = (GoLiveSessions - 1).coerceAtLeast(0)
    }

    override fun GoStats(): RelayStats = GoStatsValue.copy(GoLiveSessions = GoLiveSessions)
}

/**
 * [DeviceRepository] fake for the Devices screen, implementing the DOMAIN port.
 *
 * [GoFailOnList] lets a test drive the "repository unavailable" path: the screen
 * must degrade to an unavailable state rather than crash when the store throws.
 * Deletes are tracked in [GoDeletedIds] so a revoke can be proven independently of
 * the list mutation it causes.
 */
class FakeDeviceRepository(
    GoSeed: List<Device> = emptyList(),
    private val GoFailOnList: Boolean = false,
) : DeviceRepository {
    private val GoRows: MutableMap<String, Device> = linkedMapOf()

    val GoDeletedIds: MutableList<String> = mutableListOf()

    init {
        GoSeed.forEach { GoRows[it.GoDeviceId] = it }
    }

    fun GoSeedDevice(
        deviceId: String,
        deviceName: String,
        role: String = "GLASS",
        lastSeenMs: Long? = null,
    ) {
        GoRows[deviceId] =
            Device(
                GoDeviceId = deviceId,
                GoDeviceName = deviceName,
                GoRole = role,
                GoTokenHash = "hash-$deviceId",
                GoPairedAtMs = 1_000L,
                GoLastSeenMs = lastSeenMs,
            )
    }

    override fun GoUpsert(device: Device) {
        GoRows[device.GoDeviceId] = device
    }

    override fun GoGet(deviceId: String): Device? = GoRows[deviceId]

    override fun GoGetByTokenHash(tokenHash: String): Device? = GoRows.values.firstOrNull { it.GoTokenHash == tokenHash }

    override fun GoList(): List<Device> {
        if (GoFailOnList) throw IllegalStateException("device store unavailable")
        return GoRows.values.toList()
    }

    override fun GoTouch(
        deviceId: String,
        seenAtMs: Long,
    ) {
        GoRows[deviceId]?.let { GoRows[deviceId] = it.copy(GoLastSeenMs = seenAtMs) }
    }

    override fun GoDelete(deviceId: String) {
        GoDeletedIds.add(deviceId)
        GoRows.remove(deviceId)
    }
}

/**
 * [AppConfig] fake implementing the DOMAIN port only.
 *
 * [GoStoredKey] is what a real store would hold; the screen must never render it in
 * cleartext by default, so the value is a recognizable sentinel the test can search
 * for in the semantics tree.
 */
class FakeAppConfig(
    GoStoredKey: String = "",
    GoEngine: SttEngine = SttEngine.MOCK,
    GoRegionValue: String = AppConfig.GoDefaultRegion,
    GoMode: HotspotMode = HotspotMode.MANUAL,
) : AppConfig {
    private var GoKeyValue: String = GoStoredKey
    private var GoEngineValue: SttEngine = GoEngine
    private var GoRegionValue: String = GoRegionValue
    private var GoModeValue: HotspotMode = GoMode

    var GoSetSttAdapterCalls: Int = 0
        private set

    var GoSetRegionCalls: Int = 0
        private set

    var GoSetHotspotModeCalls: Int = 0
        private set

    override fun GoSttEngine(): SttEngine = GoEngineValue

    override fun GoSetSttEngine(kind: SttEngine) {
        GoSetSttAdapterCalls += 1
        GoEngineValue = kind
    }

    override fun GoApiKey(): String = GoKeyValue

    override fun GoSetApiKey(apiKey: String?) {
        GoKeyValue = apiKey?.trim().orEmpty()
    }

    override fun GoRegion(): String = GoRegionValue

    override fun GoSetRegion(region: String?) {
        GoSetRegionCalls += 1
        region?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
            if (it in setOf("global", "eu", "us", "au")) GoRegionValue = it
        }
    }

    override fun GoHotspotMode(): HotspotMode = GoModeValue

    override fun GoSetHotspotMode(mode: HotspotMode) {
        GoSetHotspotModeCalls += 1
        GoModeValue = mode
    }
}

/** [BatteryExemption] fake: the state is settable and every request is counted. */
class FakeBatteryExemption(
    private var GoExempt: Boolean = false,
) : BatteryExemption {
    var GoRequestCalls: Int = 0
        private set

    fun GoSetExempt(exempt: Boolean) {
        GoExempt = exempt
    }

    override fun GoIsExempt(): Boolean = GoExempt

    override fun GoRequestExemption() {
        GoRequestCalls += 1
    }
}
