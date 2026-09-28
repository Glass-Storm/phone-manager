package com.glassstorm.phonemanager.core.service

import com.glassstorm.phonemanager.core.model.Device
import com.glassstorm.phonemanager.core.service.security.TokenCodec
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Direct proof for [TokenAuthorityService] in isolation: hash-backed token
 * resolution, touch-on-verify, revocation, explicit touch, and listing. The
 * repository is the domain-port [FakeDeviceRepository]; the clock is injected.
 */
class TokenAuthorityServiceTest {
    private lateinit var repo: FakeDeviceRepository
    private lateinit var tokens: TokenAuthorityService
    private var nowMs: Long = 1_000_000L

    @Before
    fun buildService() {
        repo = FakeDeviceRepository()
        tokens = TokenAuthorityService.withClock(repo, clock = { nowMs })
        nowMs = 1_000_000L
    }

    private fun seed(
        deviceId: String,
        token: String,
    ) {
        repo.upsert(
            Device(
                deviceId = deviceId,
                deviceName = "phone-a",
                role = "phone",
                tokenHash = TokenCodec.hashToken(token),
                pairedAtMs = 1_000L,
                lastSeenMs = null,
            ),
        )
    }

    @Test
    fun `verify resolves a stored hash and bumps last seen`() {
        // Given a device whose token hash is stored
        seed("d-1", token = "secret-token")
        assertThat(repo.get("d-1")!!.lastSeenMs).isNull()

        // When the token is verified at a later instant
        nowMs = 2_000_000L
        val resolved = tokens.verifyToken("secret-token")

        // Then the right device is returned and the stored last-seen advanced
        assertThat(resolved?.deviceId).isEqualTo("d-1")
        assertThat(repo.get("d-1")!!.lastSeenMs).isEqualTo(2_000_000L)
    }

    @Test
    fun `an unknown token resolves to null`() {
        // Given a stored device
        seed("d-1", token = "secret-token")

        // When a token that was never issued is verified
        // Then it resolves to null
        assertThat(tokens.verifyToken("not-a-real-token")).isNull()
    }

    @Test
    fun `revoke removes the device so its token no longer resolves`() {
        // Given a device whose token verifies
        seed("d-1", token = "secret-token")
        assertThat(tokens.verifyToken("secret-token")).isNotNull()

        // When it is revoked
        tokens.revoke("d-1")

        // Then the token resolves to null and the listing is empty
        assertThat(tokens.verifyToken("secret-token")).isNull()
        assertThat(tokens.listPaired()).isEmpty()
    }

    @Test
    fun `touch last seen records the instant without a token`() {
        // Given a stored device with no last-seen
        seed("d-1", token = "secret-token")

        // When last-seen is recorded directly
        tokens.touchLastSeen("d-1", seenAtMs = 5_000L)

        // Then the stored value is exactly that instant
        assertThat(repo.get("d-1")!!.lastSeenMs).isEqualTo(5_000L)
    }

    @Test
    fun `list paired returns every stored device`() {
        // Given two stored devices
        seed("d-1", token = "token-a")
        seed("d-2", token = "token-b")

        // Then the listing contains exactly their ids
        assertThat(tokens.listPaired().map { it.deviceId }).containsExactly("d-1", "d-2")
    }
}
