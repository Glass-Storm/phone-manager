package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.glassstorm.phonemanager.battery.BatteryExemption
import com.glassstorm.phonemanager.core.domain.adapter.config.AppConfig
import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.network.HotspotFailure
import com.glassstorm.phonemanager.core.domain.network.HotspotUnavailableException
import com.glassstorm.phonemanager.core.domain.service.PairingService
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.model.Device
import com.glassstorm.phonemanager.core.model.HotspotInfo
import com.glassstorm.phonemanager.core.model.HotspotMode
import com.glassstorm.phonemanager.core.model.PairOutcome
import com.glassstorm.phonemanager.core.model.Pairing
import com.glassstorm.phonemanager.core.model.RelayResult
import com.glassstorm.phonemanager.core.model.RelaySession
import com.glassstorm.phonemanager.core.model.RelayStats
import com.glassstorm.phonemanager.core.model.SttEngine
import com.glassstorm.phonemanager.hub.HubStarter
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

/** [HubServer] fake: `start` always binds [boundPortValue] and flips running. */
class FakeHubServer(
    private val boundPortValue: Int = 40404,
    private val failOnRead: Boolean = false,
) : HubServer {
    var startCalls: Int = 0
        private set

    private var running: Boolean = false

    override fun start(port: Int) {
        startCalls += 1
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean {
        if (failOnRead) throw IllegalStateException("hub unavailable")
        return running
    }

    override fun boundPort(): Int {
        if (failOnRead) throw IllegalStateException("hub unavailable")
        return if (running) boundPortValue else 0
    }
}

/**
 * [HotspotController] fake.
 *
 * [failWith] makes `startHotspot` throw the real typed exception, so the
 * "could not start" path is exercised exactly as production would raise it.
 */
class FakeHotspotController(
    private val info: HotspotInfo =
        HotspotInfo(
            ssid = "EcoSys-Phone",
            passphrase = "hunter2-phone",
            gatewayIp = "192.168.43.1",
        ),
    private val failWith: HotspotFailure? = null,
    private val failOnDetect: Boolean = false,
) : HotspotController {
    private var active: Boolean = false

    var startCalls: Int = 0
        private set

    override fun startHotspot(): HotspotInfo {
        startCalls += 1
        failWith?.let { throw HotspotUnavailableException(it) }
        active = true
        return info
    }

    override fun stopHotspot() {
        active = false
    }

    override fun isActive(): Boolean = active

    override fun detectManualTether(): HotspotInfo? {
        if (failOnDetect) throw IllegalStateException("hotspot unavailable")
        return if (active) info else null
    }
}

/**
 * [PairingService] fake with the same one-window-at-a-time semantics as the real
 * service: [openWindow] REPLACES the previous window and returns the next PIN in
 * [pinSequence], so a UI that rendered a stale PIN would fail the two-open test.
 */
class FakePairingService(
    private val pinSequence: List<String> = listOf("428193", "999999"),
    private val clock: () -> Long = { 1_000_000L },
    seed: List<Device> = emptyList(),
    private val failOnList: Boolean = false,
) : PairingService {
    private val rows: MutableMap<String, Device> = linkedMapOf()

    private var window: Pairing? = null

    var openCalls: Int = 0
        private set

    init {
        seed.forEach { rows[it.deviceId] = it }
    }

    fun seedDevice(
        deviceId: String,
        deviceName: String,
        role: String = "GLASS",
    ) {
        rows[deviceId] =
            Device(
                deviceId = deviceId,
                deviceName = deviceName,
                role = role,
                tokenHash = "hash-$deviceId",
                pairedAtMs = clock(),
                lastSeenMs = null,
            )
    }

    override fun openWindow(ttlMs: Long): Pairing {
        val index = openCalls.coerceAtMost(pinSequence.lastIndex)
        openCalls += 1
        val fresh =
            Pairing(
                pin = pinSequence[index],
                expiresAtMs = clock() + ttlMs,
            )
        window = fresh
        return fresh
    }

    override fun stopWindow() {
        window = null
    }

    override fun pair(
        pin: String,
        deviceName: String,
        role: String,
    ): PairOutcome = PairOutcome.Rejected(reason = PairOutcome.REASON_PIN_INVALID)

    override fun touchLastSeen(
        deviceId: String,
        seenAtMs: Long,
    ) = Unit

    override fun revoke(deviceId: String) {
        rows.remove(deviceId)
    }

    override fun listPaired(): List<Device> {
        if (failOnList) throw IllegalStateException("pairing store unavailable")
        return rows.values.toList()
    }
}

/**
 * [StreamService] fake.
 *
 * It implements the DOMAIN port exactly like `StreamServiceImpl` would, and mints
 * a DISTINCT session id per [openSession] so a screen rendering a stale session
 * cannot pass. Counters come from [NextStats], which the test pushes through
 * [reportStats] to simulate new frames arriving on the peer between polls — the
 * screen has no other way to learn them, so a refresh loop that never runs leaves
 * the counters at zero and fails the test.
 *
 * Results are published on a [MutableSharedFlow] with `replay = 1`: the fake's
 * `results` is collected by the ViewModel while the session is live, and the
 * latest utterance is what the screen renders with its speaker label.
 */
class FakeStreamService(
    private val sessionIds: List<String> = listOf("s-1", "s-2", "s-3"),
    private val failStats: Boolean = false,
) : StreamService {
    private val resultsFlow = MutableSharedFlow<RelayResult>(replay = 1)

    private var statsValue =
        RelayStats(
            audioFrames = 0L,
            videoFrames = 0L,
            videoDropped = 0L,
            transcripts = 0L,
            liveSessions = 0,
        )

    private var openCalls: Int = 0
    private var closed: MutableList<String> = mutableListOf()

    /** How many sessions the fake currently believes are live. */
    var liveSessions: Int = 0
        private set

    fun openCount(): Int = openCalls

    fun closedIds(): List<String> = closed.toList()

    /** Replaces the snapshot [stats] returns, as if the peer had pushed more media. */
    fun reportStats(
        audioFrames: Long,
        videoFrames: Long,
        videoDropped: Long,
        transcripts: Long,
    ) {
        statsValue =
            RelayStats(
                audioFrames = audioFrames,
                videoFrames = videoFrames,
                videoDropped = videoDropped,
                transcripts = transcripts,
                liveSessions = liveSessions,
            )
    }

    /** Publish a recognized utterance, as the STT engine would mid-stream. */
    fun emitTranscript(
        text: String,
        speakerLabel: String = "Speaker 1",
    ) {
        resultsFlow.tryEmit(
            RelayResult(text = text, speakerLabel = speakerLabel, ptsMs = 0L),
        )
    }

    override fun openSession(deviceId: String): RelaySession {
        val index = openCalls.coerceAtMost(sessionIds.lastIndex)
        openCalls += 1
        liveSessions += 1
        return RelaySession(sessionId = sessionIds[index], deviceId = deviceId)
    }

    override suspend fun pushAudio(
        sessionId: String,
        audioPcm16: ByteArray,
        sampleRateHz: Int,
    ) = Unit

    override fun pushVideo(
        sessionId: String,
        h264Nal: ByteArray,
    ) = Unit

    override fun results(sessionId: String): Flow<RelayResult> = if (liveSessions > 0) resultsFlow else emptyFlow()

    override suspend fun closeSession(sessionId: String) {
        closed.add(sessionId)
        liveSessions = (liveSessions - 1).coerceAtLeast(0)
    }

    override fun stats(): RelayStats {
        if (failStats) throw IllegalStateException("relay unavailable")
        return statsValue.copy(liveSessions = liveSessions)
    }
}

/**
 * [DeviceRepository] fake for the Devices screen, implementing the DOMAIN port.
 *
 * [failOnList] lets a test drive the "repository unavailable" path: the screen
 * must degrade to an unavailable state rather than crash when the store throws.
 * Deletes are tracked in [deletedIds] so a revoke can be proven independently of
 * the list mutation it causes.
 */
class FakeDeviceRepository(
    seed: List<Device> = emptyList(),
    private val failOnList: Boolean = false,
) : DeviceRepository {
    private val rows: MutableMap<String, Device> = linkedMapOf()

    val deletedIds: MutableList<String> = mutableListOf()

    init {
        seed.forEach { rows[it.deviceId] = it }
    }

    fun seedDevice(
        deviceId: String,
        deviceName: String,
        role: String = "GLASS",
        lastSeenMs: Long? = null,
    ) {
        rows[deviceId] =
            Device(
                deviceId = deviceId,
                deviceName = deviceName,
                role = role,
                tokenHash = "hash-$deviceId",
                pairedAtMs = 1_000L,
                lastSeenMs = lastSeenMs,
            )
    }

    override fun upsert(device: Device) {
        rows[device.deviceId] = device
    }

    override fun get(deviceId: String): Device? = rows[deviceId]

    override fun getByTokenHash(tokenHash: String): Device? = rows.values.firstOrNull { it.tokenHash == tokenHash }

    override fun list(): List<Device> {
        if (failOnList) throw IllegalStateException("device store unavailable")
        return rows.values.toList()
    }

    override fun touch(
        deviceId: String,
        seenAtMs: Long,
    ) {
        rows[deviceId]?.let { rows[deviceId] = it.copy(lastSeenMs = seenAtMs) }
    }

    override fun delete(deviceId: String) {
        deletedIds.add(deviceId)
        rows.remove(deviceId)
    }
}

/**
 * [AppConfig] fake implementing the DOMAIN port only.
 *
 * [storedKey] is what a real store would hold; the screen must never render it in
 * cleartext by default, so the value is a recognizable sentinel the test can search
 * for in the semantics tree.
 */
class FakeAppConfig(
    storedKey: String = "",
    engine: SttEngine = SttEngine.MOCK,
    regionValue: String = AppConfig.DEFAULT_REGION,
    mode: HotspotMode = HotspotMode.MANUAL,
    private val failReads: Boolean = false,
) : AppConfig {
    private var keyValue: String = storedKey
    private var engineValue: SttEngine = engine
    private var regionValue: String = regionValue
    private var modeValue: HotspotMode = mode

    var setSttAdapterCalls: Int = 0
        private set

    var setRegionCalls: Int = 0
        private set

    var setHotspotModeCalls: Int = 0
        private set

    override fun sttEngine(): SttEngine {
        if (failReads) throw IllegalStateException("config store unavailable")
        return engineValue
    }

    override fun setSttEngine(kind: SttEngine) {
        setSttAdapterCalls += 1
        engineValue = kind
    }

    override fun apiKey(): String = keyValue

    override fun setApiKey(apiKey: String?) {
        keyValue = apiKey?.trim().orEmpty()
    }

    override fun region(): String = regionValue

    override fun setRegion(region: String?) {
        setRegionCalls += 1
        region?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
            if (it in setOf("global", "eu", "us", "au")) regionValue = it
        }
    }

    override fun hotspotMode(): HotspotMode = modeValue

    override fun setHotspotMode(mode: HotspotMode) {
        setHotspotModeCalls += 1
        modeValue = mode
    }
}

/** [BatteryExemption] fake: the state is settable and every request is counted. */
class FakeBatteryExemption(
    private var exempt: Boolean = false,
) : BatteryExemption {
    var requestCalls: Int = 0
        private set

    fun setExempt(exempt: Boolean) {
        this.exempt = exempt
    }

    override fun isExempt(): Boolean = exempt

    override fun requestExemption() {
        requestCalls += 1
    }
}

/**
 * [HubStarter] fake: every start is counted and [onStart] runs as the production
 * [HubServer] start would.
 *
 * The default `onStart` is a no-op, so a test that only wants to count invocations
 * can assert [startCalls] without bringing a hub up. A test that also renders the
 * bound port passes `{ hub.start(0) }`, which is exactly what the real foreground
 * service ends up doing.
 */
class FakeHubStarter(
    private val onStart: () -> Unit = {},
) : HubStarter {
    var startCalls: Int = 0
        private set

    override fun start() {
        startCalls += 1
        onStart()
    }
}

/**
 * A [ViewModelProvider.Factory] over explicitly-constructed ViewModels, keyed by
 * runtime class. The screen tests build the ViewModel they want to exercise and
 * hand it to the screen through this factory, so the failure-path cases can inject
 * a port that FAILS rather than an absent one (a missing port is impossible under
 * compile-time DI).
 */
fun viewModelFactory(vararg models: ViewModel): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = models.first { modelClass.isInstance(it) } as T
    }
