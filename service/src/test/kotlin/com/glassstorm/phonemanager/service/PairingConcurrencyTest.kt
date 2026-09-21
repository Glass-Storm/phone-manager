package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.Register
import com.glassstorm.phonemanager.domain.dto.PairOutcome
import com.glassstorm.phonemanager.domain.dto.Pairing
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    val deadline: Timeout = Timeout.seconds(60)

    private lateinit var ctx: Context
    private lateinit var repo: FakeDeviceRepository
    private lateinit var pairing: PairingServiceImpl

    @Before
    fun buildService() {
        ctx = Context()
        repo = FakeDeviceRepository()
        Register<DeviceRepository>(ctx, repo)
        pairing = PairingServiceImpl(ctx, clock = { System.currentTimeMillis() })
    }

    // ------------------------------------------------- case 1: single-use under race

    @Test
    fun `concurrent attempts with the correct pin mint exactly one token`() {
        // Given an open single-use window and N callers lined up on one barrier
        val window = pairing.openWindow(ttlMs = 120_000L)
        val workers = 32
        val gate = CyclicBarrier(workers)
        val pool: ExecutorService = Executors.newFixedThreadPool(workers)

        try {
            // When all N fire pair with the CORRECT pin simultaneously
            val futures =
                (0 until workers).map { index ->
                    pool.submit<PairOutcome> {
                        gate.await()
                        pairing.pair(window.pin, "peer-$index", "phone")
                    }
                }
            val outcomes = futures.map { it.get() }

            // Then EXACTLY one succeeded and every other caller saw the PIN consumed
            val okCount = outcomes.count { it is PairOutcome.Ok }
            val consumedCount =
                outcomes.count {
                    it is PairOutcome.Rejected && it.reason == PairOutcome.REASON_PIN_CONSUMED
                }
            assertThat(okCount).isEqualTo(1)
            assertThat(consumedCount).isEqualTo(workers - 1)

            // And the repository holds exactly ONE device, whose token verifies
            assertThat(repo.list()).hasSize(1)
            val ok = outcomes.filterIsInstance<PairOutcome.Ok>().single()
            assertThat(pairing.verifyToken(ok.token)?.deviceId).isEqualTo(ok.deviceId)
        } finally {
            pool.shutdownNow()
        }
    }

    // ----------------------------------------------------- case 2: attempt cap under race

    @Test
    fun `concurrent wrong pin attempts cannot undercount the attempt cap`() {
        // Given an open window and N callers lined up to guess a WRONG pin at once
        val window = pairing.openWindow(ttlMs = 120_000L)
        val wrong = window.pin.pinOtherThan(window)
        val workers = 32
        val gate = CyclicBarrier(workers)
        val pool: ExecutorService = Executors.newFixedThreadPool(workers)

        try {
            // When all N wrong attempts land together
            val futures =
                (0 until workers).map { index ->
                    pool.submit<PairOutcome> {
                        gate.await()
                        pairing.pair(wrong, "peer-$index", "phone")
                    }
                }
            futures.forEach { it.get() }

            // Then the cap was reached, so even the CORRECT pin is now locked out
            val late = pairing.pair(window.pin, "late-peer", "phone")
            assertThat(late).isInstanceOf(PairOutcome.Rejected::class.java)
            assertThat((late as PairOutcome.Rejected).reason)
                .isEqualTo(PairOutcome.REASON_PIN_LOCKED)

            // And no wrong attempt ever persisted a device
            assertThat(repo.list()).isEmpty()
        } finally {
            pool.shutdownNow()
        }
    }

    /** Returns a 6-digit PIN string that is guaranteed different from the window's. */
    private fun String.pinOtherThan(window: Pairing): String {
        val candidate = if (this == window.pin) "000000" else this
        return if (candidate == window.pin) "111111" else candidate
    }
}
