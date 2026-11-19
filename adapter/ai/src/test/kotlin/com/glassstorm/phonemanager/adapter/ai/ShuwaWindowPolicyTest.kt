package com.glassstorm.phonemanager.adapter.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Contract tests for the pure [ShuwaWindowPolicy].
 *
 * The port receives one still per call, so the policy is the only thing that decides when a 16-frame
 * window is complete, whether a gloss has enough consecutive agreement to be trusted, and when a
 * repeated gloss is a new utterance. These tests drive the exact cadence the relay produces (75%
 * overlap at the default width) and pin every rule on the host JVM: no engine, no native, no Android.
 */
class ShuwaWindowPolicyTest {
    @Test
    fun `the first window completes at sixteen stills and then every stride`() {
        val policy = ShuwaWindowPolicy()
        val boundaries = mutableListOf<Int>()

        repeat(STAIRS_STILLS) { index ->
            if (policy.onFrame()) {
                boundaries.add(index + 1)
            }
        }

        assertThat(boundaries).containsExactly(16, 20, 24, 28, 32, 36).inOrder()
    }

    @Test
    fun `no window completes before the window is full`() {
        val policy = ShuwaWindowPolicy()

        repeat(ShuwaWindowPolicy.DEFAULT_WINDOW_FRAMES - 1) {
            assertThat(policy.onFrame()).isFalse()
        }
        assertThat(policy.onFrame()).isTrue()
    }

    @Test
    fun `a stride of one evaluates every still once the window is full`() {
        val policy = ShuwaWindowPolicy(stride = 1)
        val boundaries = mutableListOf<Int>()

        repeat(STAIRS_STILLS) { index ->
            if (policy.onFrame()) {
                boundaries.add(index + 1)
            }
        }

        assertThat(boundaries).containsExactlyElementsIn(16..36).inOrder()
    }

    @Test
    fun `a single window is not enough to emit a gloss`() {
        val policy = ShuwaWindowPolicy()

        assertThat(policy.accept(LABEL, atMs = 1_000)).isFalse()
        assertThat(policy.accept(LABEL, atMs = 1_400)).isTrue()
    }

    @Test
    fun `disagreeing windows never emit`() {
        val policy = ShuwaWindowPolicy()

        assertThat(policy.accept(LABEL, atMs = 1_000)).isFalse()
        assertThat(policy.accept(OTHER_LABEL, atMs = 1_400)).isFalse()
        assertThat(policy.accept(LABEL, atMs = 1_800)).isFalse()
        assertThat(policy.accept(OTHER_LABEL, atMs = 2_200)).isFalse()
    }

    @Test
    fun `an interrupting label resets the agreement count`() {
        val policy = ShuwaWindowPolicy()

        assertThat(policy.accept(LABEL, atMs = 1_000)).isFalse()
        assertThat(policy.accept(OTHER_LABEL, atMs = 1_400)).isFalse()
        assertThat(policy.accept(OTHER_LABEL, atMs = 1_800)).isTrue()
    }

    @Test
    fun `a held sign is reported once per duplicate window`() {
        val policy = ShuwaWindowPolicy(requiredAgreements = 1)

        assertThat(policy.accept(LABEL, atMs = 1_000)).isTrue()
        assertThat(policy.accept(LABEL, atMs = 1_500)).isFalse()
        assertThat(policy.accept(LABEL, atMs = 2_100)).isFalse()
        assertThat(policy.accept(LABEL, atMs = 2_200)).isTrue()
    }

    @Test
    fun `a different sign is reported immediately`() {
        val policy = ShuwaWindowPolicy(requiredAgreements = 1)

        assertThat(policy.accept(LABEL, atMs = 1_000)).isTrue()
        assertThat(policy.accept(OTHER_LABEL, atMs = 1_200)).isTrue()
        assertThat(policy.accept(LABEL, atMs = 1_400)).isTrue()
    }

    @Test
    fun `zero duplicate window reports every window`() {
        val policy = ShuwaWindowPolicy(duplicateWindowMs = 0, requiredAgreements = 1)

        assertThat(policy.accept(LABEL, atMs = 1_000)).isTrue()
        assertThat(policy.accept(LABEL, atMs = 1_000)).isTrue()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a stride larger than the window is rejected`() {
        ShuwaWindowPolicy(windowFrames = 16, stride = 17)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a single still window is rejected`() {
        ShuwaWindowPolicy(windowFrames = 1, stride = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero required agreements is rejected`() {
        ShuwaWindowPolicy(requiredAgreements = 0)
    }

    private companion object {
        const val STAIRS_STILLS = 36

        const val LABEL = "Hksl_welcome"

        const val OTHER_LABEL = "Hksl_finish"
    }
}
