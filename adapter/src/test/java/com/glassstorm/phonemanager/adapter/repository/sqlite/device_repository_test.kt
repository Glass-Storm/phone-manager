package com.glassstorm.phonemanager.adapter.repository.sqlite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.dto.Device
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric tests for the real SQLite [DeviceRepository] implementation.
 *
 * Every assertion inspects ACTUAL database state (query results / row counts),
 * never mock interactions.
 */
@RunWith(RobolectricTestRunner::class)
class SqliteDeviceRepositoryTest {

    private lateinit var GoContext: Context
    private lateinit var GoDatabaseName: String
    private lateinit var GoRepo: SqliteDeviceRepository

    @Before
    fun setUp() {
        GoContext = ApplicationProvider.getApplicationContext()
        GoDatabaseName = "device-repo-test-${System.nanoTime()}.db"
        GoRepo = SqliteDeviceRepository(GoContext, GoDatabaseName)
    }

    @After
    fun tearDown() {
        GoRepo.close()
        GoContext.deleteDatabase(GoDatabaseName)
    }

    private fun GoPairedDevice(
        id: String,
        name: String = "glass",
        role: String = "GLASS",
        tokenHash: String = "hash-$id",
        pairedAtMs: Long = 1_000L,
        lastSeenMs: Long? = null,
    ): Device = Device(
        GoDeviceId = id,
        GoDeviceName = name,
        GoRole = role,
        GoTokenHash = tokenHash,
        GoPairedAtMs = pairedAtMs,
        GoLastSeenMs = lastSeenMs,
    )

    private fun GoRowCount(): Int =
        GoRepo.readableDatabase.rawQuery("SELECT COUNT(*) FROM paired_device", null).use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun GoTableNames(): Set<String> =
        GoRepo.readableDatabase
            .rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null)
            .use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }

    @Test
    fun `insert then get then list then delete round-trips through the database`() {
        // Given a device written to a real database file
        val GoDevice = GoPairedDevice(id = "d-1")
        GoRepo.GoUpsert(GoDevice)

        // When read back by id and by list
        // Then both reflect the persisted row
        assertThat(GoRepo.GoGet("d-1")).isEqualTo(GoDevice)
        assertThat(GoRepo.GoList()).containsExactly(GoDevice)
        assertThat(GoRowCount()).isEqualTo(1)

        // When the device is revoked
        GoRepo.GoDelete("d-1")

        // Then the row is gone from the database
        assertThat(GoRepo.GoGet("d-1")).isNull()
        assertThat(GoRepo.GoList()).isEmpty()
        assertThat(GoRowCount()).isEqualTo(0)
    }

    @Test
    fun `get by token hash round-trips and survives a null last-seen`() {
        // Given two paired devices with distinct token hashes, never seen
        GoRepo.GoUpsert(GoPairedDevice(id = "d-1", tokenHash = "hash-one"))
        GoRepo.GoUpsert(GoPairedDevice(id = "d-2", name = "daemon", role = "DAEMON", tokenHash = "hash-two"))

        // When looked up by the second hash
        // Then only that row returns, with its null last-seen preserved
        assertThat(GoRepo.GoGetByTokenHash("hash-two")).isEqualTo(
            GoPairedDevice(id = "d-2", name = "daemon", role = "DAEMON", tokenHash = "hash-two")
        )
        assertThat(GoRepo.GoGetByTokenHash("hash-two")?.GoLastSeenMs).isNull()
        assertThat(GoRepo.GoGetByTokenHash("hash-absent")).isNull()
    }

    @Test
    fun `inserting a duplicate device id upserts and keeps a single row`() {
        // Given one stored device
        GoRepo.GoUpsert(GoPairedDevice(id = "d-1", name = "old", tokenHash = "hash-old"))
        assertThat(GoRowCount()).isEqualTo(1)

        // When the same id is inserted again with new values
        GoRepo.GoUpsert(GoPairedDevice(id = "d-1", name = "new", role = "DAEMON", tokenHash = "hash-new"))

        // Then no duplicate row was created and every field was updated
        assertThat(GoRowCount()).isEqualTo(1)
        val GoUpdated = GoRepo.GoGet("d-1")
        assertThat(GoUpdated?.GoDeviceName).isEqualTo("new")
        assertThat(GoUpdated?.GoRole).isEqualTo("DAEMON")
        assertThat(GoUpdated?.GoTokenHash).isEqualTo("hash-new")
    }

    @Test
    fun `touch updates last seen ms and leaves the rest intact`() {
        // Given a persisted device that has never been seen
        GoRepo.GoUpsert(GoPairedDevice(id = "d-1"))
        assertThat(GoRepo.GoGet("d-1")?.GoLastSeenMs).isNull()

        // When it heartbeats
        GoRepo.GoTouch("d-1", 42_424L)

        // Then only the last-seen instant moved in the database
        val GoTouched = GoRepo.GoGet("d-1")!!
        assertThat(GoTouched.GoLastSeenMs).isEqualTo(42_424L)
        assertThat(GoTouched.GoDeviceName).isEqualTo("glass")
        assertThat(GoTouched.GoPairedAtMs).isEqualTo(1_000L)
    }

    @Test
    fun `touch of an unknown device is a no-op`() {
        // Given an empty table
        // When a non-existent device is touched
        GoRepo.GoTouch("missing", 1L)

        // Then nothing was created
        assertThat(GoRowCount()).isEqualTo(0)
    }

    @Test
    fun `attempt bookkeeping increments then resets`() {
        // Given a device with no attempts yet
        assertThat(GoRepo.GoReadAttempt("d-1")).isNull()

        // When attempts are recorded with a stable first-attempt instant
        assertThat(GoRepo.GoIncrementAttempt("d-1", 5_000L)).isEqualTo(1)
        assertThat(GoRepo.GoIncrementAttempt("d-1", 6_000L)).isEqualTo(2)
        assertThat(GoRepo.GoIncrementAttempt("d-1", 7_000L)).isEqualTo(3)

        // Then the count and the original first-attempt instant are persisted
        val GoAttempt = GoRepo.GoReadAttempt("d-1")
        assertThat(GoAttempt?.GoAttemptCount).isEqualTo(3)
        assertThat(GoAttempt?.GoFirstAttemptMs).isEqualTo(5_000L)

        // When the attempts are reset after a successful pairing
        GoRepo.GoResetAttempt("d-1")

        // Then the row is removed
        assertThat(GoRepo.GoReadAttempt("d-1")).isNull()
    }

    @Test
    fun `onCreate provisions both required tables`() {
        // Given the helper opened the database for the first time
        GoRepo.writableDatabase

        // Then both tables exist in the schema
        assertThat(GoTableNames()).containsAtLeast("paired_device", "pairing_attempt")
    }

    @Test
    fun `the sqlite adapter satisfies the domain port`() {
        // Given the adapter viewed through the domain interface
        val GoPort: DeviceRepository = GoRepo

        // When used through the port
        GoPort.GoUpsert(GoPairedDevice(id = "d-9", tokenHash = "hash-nine"))

        // Then the persisted state is observable through the same port
        assertThat(GoPort.GoGetByTokenHash("hash-nine")?.GoDeviceId).isEqualTo("d-9")
    }
}
