package com.glassstorm.phonemanager

import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T14 harness: the REAL Python `ecosys_pi` CLI driven against the REAL hub.
 *
 * ## Parked (T9)
 *
 * This harness was written for the PIN-pairing world: it launched the Pi CLI with
 * `--pin`, asserted `pair-ok`/`heartbeat-ok`, and exercised wrong-PIN and
 * revoked-token refusal. T9 removed pairing/PIN/bearer-token from the hub runtime,
 * so those legs no longer describe the hub, and the Pi client has not yet dropped
 * pairing (T14).
 *
 * The harness is therefore IGNORED here — not deleted — and is reworked onto the
 * unauthenticated `stream_open` contract by T14 (Pi) and T23 (cross-repo E2E). The
 * child-process runner it uses (`EcosysPi`, `runEcosysPi`, `CliRun`) is unchanged
 * and stays available for the rework.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@Ignore("Parked by T9: the hub no longer pairs; reworked on the unauthenticated stream by T14/T23")
class EcosysPiE2eTest {
    @Test
    fun `parked pending the Pi client dropping pairing`() {
        // Intentionally empty: the harness body is restored by T23 against the
        // unauthenticated stream contract.
    }
}
