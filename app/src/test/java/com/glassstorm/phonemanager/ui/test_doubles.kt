package com.glassstorm.phonemanager.ui

import com.glassstorm.phonemanager.domain.adapter.network.HotspotController
import com.glassstorm.phonemanager.domain.adapter.transport.HubServer
import com.glassstorm.phonemanager.domain.dto.Device
import com.glassstorm.phonemanager.domain.dto.HotspotInfo
import com.glassstorm.phonemanager.domain.dto.PairOutcome
import com.glassstorm.phonemanager.domain.dto.Pairing
import com.glassstorm.phonemanager.domain.network.HotspotFailure
import com.glassstorm.phonemanager.domain.network.HotspotUnavailableException
import com.glassstorm.phonemanager.domain.service.PairingService

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
