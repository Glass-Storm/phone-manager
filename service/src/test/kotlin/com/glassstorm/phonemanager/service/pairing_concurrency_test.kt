package com.glassstorm.phonemanager.service

import com.google.common.truth.Truth.assertThat
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.Register
import com.glassstorm.phonemanager.domain.dto.PairOutcome
import com.glassstorm.phonemanager.domain.dto.Pairing
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Concurrency proof for [PairingServiceImpl]'s single-use window.
 *
 * grpc-kotlin dispatches `Pair` calls concurrently, so the window's
 * check-and-consume and the failed-attempt increment must each be atomic. These
 * tests fire many `Pair` calls at once — released together by a [CyclicBarrier]
 * on a real thread pool, so the calls genuinely overlap — and assert the
 * INVARIANT, not merely that the threads ran:
 *
 *  1. one single-use PIN mints EXACTLY one token and persists exactly one device;
 *  2. concurrent wrong PINs cannot lose attempt increments and slip under the cap.
 *
 * The assertion is exact (`isEqualTo(1)`, `isEqualTo(N - 1)`). It is never
 * weakened to `atLeast(1)`: only a genuinely serialized implementation passes.
 */
class PairingConcurrencyTest {

    @get:Rule
    val GoDeadline: Timeout = Timeout.seconds(60)

    private lateinit var GoCtx: Context
    private lateinit var GoRepo: FakeDeviceRepository
    private lateinit var GoPairing: PairingServiceImpl

    @Before
    fun GoBuildService() {
        GoCtx = Context()
        GoRepo = FakeDeviceRepository()
        Register<DeviceRepository>(GoCtx, GoRepo)
        GoPairing = PairingServiceImpl(GoCtx, GoClock = { System.currentTimeMillis() })
    }

    // ------------------------------------------------- case 1: single-use under race

    @Test
    fun `concurrent attempts with the correct pin mint exactly one token`() {
        // Given an open single-use window and N callers lined up on one barrier
        val GoWindow = GoPairing.GoOpenWindow(ttlMs = 120_000L)
        val GoWorkers = 32
        val GoGate = CyclicBarrier(GoWorkers)
        val GoPool: ExecutorService = Executors.newFixedThreadPool(GoWorkers)

        try {
            // When all N fire GoPair with the CORRECT pin simultaneously
            val GoFutures = (0 until GoWorkers).map { GoIndex ->
                GoPool.submit<PairOutcome> {
                    GoGate.await()
                    GoPairing.GoPair(GoWindow.GoPin, "peer-$GoIndex", "phone")
                }
            }
            val GoOutcomes = GoFutures.map { it.get() }

            // Then EXACTLY one succeeded and every other caller saw the PIN consumed
            val GoOkCount = GoOutcomes.count { it is PairOutcome.GoOk }
            val GoConsumedCount = GoOutcomes.count {
                it is PairOutcome.GoRejected && it.GoReason == PairOutcome.GoReasonPinConsumed
            }
            assertThat(GoOkCount).isEqualTo(1)
            assertThat(GoConsumedCount).isEqualTo(GoWorkers - 1)

            // And the repository holds exactly ONE device, whose token verifies
            assertThat(GoRepo.GoList()).hasSize(1)
            val GoOk = GoOutcomes.filterIsInstance<PairOutcome.GoOk>().single()
            assertThat(GoPairing.GoVerifyToken(GoOk.GoToken)?.GoDeviceId).isEqualTo(GoOk.GoDeviceId)
        } finally {
            GoPool.shutdownNow()
        }
    }

    // ----------------------------------------------------- case 2: attempt cap under race

    @Test
    fun `concurrent wrong pin attempts cannot undercount the attempt cap`() {
        // Given an open window and N callers lined up to guess a WRONG pin at once
        val GoWindow = GoPairing.GoOpenWindow(ttlMs = 120_000L)
        val GoWrong = GoWindow.GoPin.GoPinOtherThan(GoWindow)
        val GoWorkers = 32
        val GoGate = CyclicBarrier(GoWorkers)
        val GoPool: ExecutorService = Executors.newFixedThreadPool(GoWorkers)

        try {
            // When all N wrong attempts land together
            val GoFutures = (0 until GoWorkers).map { GoIndex ->
                GoPool.submit<PairOutcome> {
                    GoGate.await()
                    GoPairing.GoPair(GoWrong, "peer-$GoIndex", "phone")
                }
            }
            GoFutures.forEach { it.get() }

            // Then the cap was reached, so even the CORRECT pin is now locked out
            val GoLate = GoPairing.GoPair(GoWindow.GoPin, "late-peer", "phone")
            assertThat(GoLate).isInstanceOf(PairOutcome.GoRejected::class.java)
            assertThat((GoLate as PairOutcome.GoRejected).GoReason)
                .isEqualTo(PairOutcome.GoReasonPinLocked)

            // And no wrong attempt ever persisted a device
            assertThat(GoRepo.GoList()).isEmpty()
        } finally {
            GoPool.shutdownNow()
        }
    }

    /** Returns a 6-digit PIN string that is guaranteed different from the window's. */
    private fun String.GoPinOtherThan(window: Pairing): String {
        val GoCandidate = if (this == window.GoPin) "000000" else this
        return if (GoCandidate == window.GoPin) "111111" else GoCandidate
    }
}
