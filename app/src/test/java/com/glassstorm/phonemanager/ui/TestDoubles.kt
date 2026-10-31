package com.glassstorm.phonemanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.glassstorm.phonemanager.battery.BatteryExemption
import com.glassstorm.phonemanager.core.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.core.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.core.domain.network.HotspotFailure
import com.glassstorm.phonemanager.core.domain.network.HotspotUnavailableException
import com.glassstorm.phonemanager.core.domain.service.StreamService
import com.glassstorm.phonemanager.core.model.HotspotInfo
import com.glassstorm.phonemanager.core.model.LivePeer
import com.glassstorm.phonemanager.core.model.PeerRole
import com.glassstorm.phonemanager.core.model.RelayOutput
import com.glassstorm.phonemanager.core.model.RelayResult
import com.glassstorm.phonemanager.core.model.RelaySession
import com.glassstorm.phonemanager.core.model.RelayStats
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
    initiallyRunning: Boolean = false,
) : HubServer {
    var startCalls: Int = 0
        private set

    private var running: Boolean = initiallyRunning

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
    private val manualTetherInfo: HotspotInfo? = null,
    initiallyActive: Boolean = false,
    private val startDelayMs: Long = 0L,
    private val stopDelayMs: Long = 0L,
) : HotspotController {
    private var active: Boolean = initiallyActive

    var startCalls: Int = 0
        private set

    var stopCalls: Int = 0
        private set

    override fun startHotspot(): HotspotInfo {
        startCalls += 1
        if (startDelayMs > 0L) Thread.sleep(startDelayMs)
        failWith?.let { throw HotspotUnavailableException(it) }
        active = true
        return info
    }

    override fun stopHotspot() {
        stopCalls += 1
        if (stopDelayMs > 0L) Thread.sleep(stopDelayMs)
        active = false
    }

    override fun isActive(): Boolean = active

    override fun detectManualTether(): HotspotInfo? {
        if (failOnDetect) throw IllegalStateException("hotspot unavailable")
        return manualTetherInfo ?: if (active) info else null
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
 *
 * The process-wide diagnostics flow has NO replay by default, matching
 * production: a fresh ViewModel must not be seeded by an output emitted before it
 * attached. A test that models a relay which DOES replay its latest output (the
 * display race where a replayed result arrives before the first poll) opts in with
 * [outputReplay] = 1 and publishes with [emitTranscript] before construction.
 */
class FakeStreamService(
    private val sessionIds: List<String> = listOf("s-1", "s-2", "s-3"),
    private val failStats: Boolean = false,
    outputReplay: Int = 0,
) : StreamService {
    private val resultsFlow = MutableSharedFlow<RelayOutput>(replay = 1)
    private val outputsFlow = MutableSharedFlow<RelayOutput>(replay = outputReplay, extraBufferCapacity = 64)

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

    /** The peers the fake reports as attached to live sessions. */
    var livePeersValue: List<LivePeer> = emptyList()
        private set

    /** How many sessions the fake currently believes are live. */
    var liveSessions: Int = 0
        private set

    fun openCount(): Int = openCalls

    fun closedIds(): List<String> = closed.toList()

    /** Replaces the peers [livePeers] returns, as if a peer had just connected. */
    fun reportLivePeers(peers: List<LivePeer>) {
        livePeersValue = peers
    }

    /** Publish one relay output on the shared diagnostics flow. */
    fun emitOutput(output: RelayOutput) {
        outputsFlow.tryEmit(output)
    }

    /** Replaces the snapshot [stats] returns, as if the peer had pushed more media. */
    fun reportStats(
        audioFrames: Long,
        videoFrames: Long,
        videoDropped: Long,
        transcripts: Long,
        imageFrames: Long = 0L,
        gestures: Long = 0L,
    ) {
        statsValue =
            RelayStats(
                audioFrames = audioFrames,
                videoFrames = videoFrames,
                videoDropped = videoDropped,
                transcripts = transcripts,
                liveSessions = liveSessions,
                imageFrames = imageFrames,
                gestures = gestures,
            )
    }

    /** Publish a recognized utterance, as the speech engine would mid-stream. */
    fun emitTranscript(
        text: String,
        speakerLabel: String = "Speaker 1",
    ) {
        val output = RelayOutput.Speech(RelayResult(text = text, speakerLabel = speakerLabel, ptsMs = 0L))
        resultsFlow.tryEmit(output)
        outputsFlow.tryEmit(output)
    }

    override fun openSession(deviceId: String): RelaySession {
        val index = openCalls.coerceAtMost(sessionIds.lastIndex)
        openCalls += 1
        liveSessions += 1
        return RelaySession(sessionId = sessionIds[index], deviceId = deviceId)
    }

    override fun bindRole(
        sessionId: String,
        role: PeerRole,
    ) = Unit

    override suspend fun pushAudio(
        sessionId: String,
        audioPcm16: ByteArray,
        sampleRateHz: Int,
    ) = Unit

    override fun pushVideo(
        sessionId: String,
        h264Nal: ByteArray,
    ) = Unit

    override suspend fun pushImage(
        sessionId: String,
        jpeg: ByteArray,
        ptsMs: Long,
    ) = Unit

    override suspend fun pushEnroll(
        sessionId: String,
        name: String,
        pcm16: ByteArray,
        ptsMs: Long,
    ) = Unit

    override fun results(sessionId: String): Flow<RelayOutput> = if (liveSessions > 0) resultsFlow else emptyFlow()

    override fun outputs(): Flow<RelayOutput> = outputsFlow

    override fun livePeers(): List<LivePeer> = livePeersValue

    override suspend fun closeSession(sessionId: String) {
        closed.add(sessionId)
        liveSessions = (liveSessions - 1).coerceAtLeast(0)
    }

    override fun stats(): RelayStats {
        if (failStats) throw IllegalStateException("relay unavailable")
        return statsValue.copy(liveSessions = liveSessions)
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
 * [HubStarter] fake: every start/stop is counted and [onStart]/[onStop] run as the
 * production service would.
 *
 * The default hooks are no-ops, so a test that only wants to count invocations can
 * assert [startCalls]/[stopCalls] without bringing a hub up. A test that also
 * renders the bound port passes `{ hub.start(0) }`, which is exactly what the real
 * foreground service ends up doing.
 */
class FakeHubStarter(
    private val startFailure: Throwable? = null,
    private val onStop: () -> Unit = {},
    private val onStart: () -> Unit = {},
) : HubStarter {
    var startCalls: Int = 0
        private set

    var stopCalls: Int = 0
        private set

    override fun start() {
        startCalls += 1
        startFailure?.let { throw it }
        onStart()
    }

    override fun stop() {
        stopCalls += 1
        onStop()
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
