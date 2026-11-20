package com.glassstorm.phonemanager.adapter.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Contract tests for the pure [ShuwaWindowPolicy].
 *
 * Burst segmentation lives in `AiShuwaGesturePort`; this policy only suppresses a repeated gloss.
 * The tests pin the duplicate window on the host JVM: no engine, no native, no Android.
 */
class ShuwaWindowPolicyTest {
    @Test
    fun `a held sign is reported once per duplicate window`() {
        val policy = ShuwaWindowPolicy()

        assertThat(policy.accept(LABEL, atMs = 1_000)).isTrue()
        assertThat(policy.accept(LABEL, atMs = 1_500)).isFalse()
        assertThat(policy.accept(LABEL, atMs = 2_100)).isFalse()
        assertThat(policy.accept(LABEL, atMs = 2_200)).isTrue()
    }

    @Test
    fun `a different sign is reported immediately`() {
        val policy = ShuwaWindowPolicy()

        assertThat(policy.accept(LABEL, atMs = 1_000)).isTrue()
        assertThat(policy.accept(OTHER_LABEL, atMs = 1_200)).isTrue()
        assertThat(policy.accept(LABEL, atMs = 1_400)).isTrue()
    }

    @Test
    fun `zero duplicate window reports every burst`() {
        val policy = ShuwaWindowPolicy(duplicateWindowMs = 0)

        assertThat(policy.accept(LABEL, atMs = 1_000)).isTrue()
        assertThat(policy.accept(LABEL, atMs = 1_000)).isTrue()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative duplicate window is rejected`() {
        ShuwaWindowPolicy(duplicateWindowMs = -1)
    }

    private companion object {
        const val LABEL = "Hksl_welcome"

        const val OTHER_LABEL = "Hksl_write"
    }
}
