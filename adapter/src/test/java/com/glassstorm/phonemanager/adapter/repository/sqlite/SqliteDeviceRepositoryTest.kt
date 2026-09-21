package com.glassstorm.phonemanager.adapter.repository.sqlite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.model.Device
import com.google.common.truth.Truth.assertThat
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
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var repo: SqliteDeviceRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "device-repo-test-${System.nanoTime()}.db"
        repo = SqliteDeviceRepository(context, databaseName)
    }

    @After
    fun tearDown() {
        repo.close()
        context.deleteDatabase(databaseName)
    }

    private fun pairedDevice(
        id: String,
        name: String = "glass",
        role: String = "GLASS",
        tokenHash: String = "hash-$id",
        pairedAtMs: Long = 1_000L,
        lastSeenMs: Long? = null,
    ): Device =
        Device(
            deviceId = id,
            deviceName = name,
            role = role,
            tokenHash = tokenHash,
            pairedAtMs = pairedAtMs,
            lastSeenMs = lastSeenMs,
        )

    private fun rowCount(): Int =
        repo.readableDatabase.rawQuery("SELECT COUNT(*) FROM paired_device", null).use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun tableNames(): Set<String> =
        repo.readableDatabase
            .rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null)
            .use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }

    @Test
    fun `insert then get then list then delete round-trips through the database`() {
        // Given a device written to a real database file
        val device = pairedDevice(id = "d-1")
        repo.upsert(device)

        // When read back by id and by list
        // Then both reflect the persisted row
        assertThat(repo.get("d-1")).isEqualTo(device)
        assertThat(repo.list()).containsExactly(device)
        assertThat(rowCount()).isEqualTo(1)

        // When the device is revoked
        repo.delete("d-1")

        // Then the row is gone from the database
        assertThat(repo.get("d-1")).isNull()
        assertThat(repo.list()).isEmpty()
        assertThat(rowCount()).isEqualTo(0)
    }

    @Test
    fun `get by token hash round-trips and survives a null last-seen`() {
        // Given two paired devices with distinct token hashes, never seen
        repo.upsert(pairedDevice(id = "d-1", tokenHash = "hash-one"))
        repo.upsert(pairedDevice(id = "d-2", name = "daemon", role = "DAEMON", tokenHash = "hash-two"))

        // When looked up by the second hash
        // Then only that row returns, with its null last-seen preserved
        assertThat(repo.getByTokenHash("hash-two")).isEqualTo(
            pairedDevice(id = "d-2", name = "daemon", role = "DAEMON", tokenHash = "hash-two"),
        )
        assertThat(repo.getByTokenHash("hash-two")?.lastSeenMs).isNull()
        assertThat(repo.getByTokenHash("hash-absent")).isNull()
    }

    @Test
    fun `inserting a duplicate device id upserts and keeps a single row`() {
        // Given one stored device
        repo.upsert(pairedDevice(id = "d-1", name = "old", tokenHash = "hash-old"))
        assertThat(rowCount()).isEqualTo(1)

        // When the same id is inserted again with new values
        repo.upsert(pairedDevice(id = "d-1", name = "new", role = "DAEMON", tokenHash = "hash-new"))

        // Then no duplicate row was created and every field was updated
        assertThat(rowCount()).isEqualTo(1)
        val updated = repo.get("d-1")
        assertThat(updated?.deviceName).isEqualTo("new")
        assertThat(updated?.role).isEqualTo("DAEMON")
        assertThat(updated?.tokenHash).isEqualTo("hash-new")
    }

    @Test
    fun `touch updates last seen ms and leaves the rest intact`() {
        // Given a persisted device that has never been seen
        repo.upsert(pairedDevice(id = "d-1"))
        assertThat(repo.get("d-1")?.lastSeenMs).isNull()

        // When it heartbeats
        repo.touch("d-1", 42_424L)

        // Then only the last-seen instant moved in the database
        val touched = repo.get("d-1")!!
        assertThat(touched.lastSeenMs).isEqualTo(42_424L)
        assertThat(touched.deviceName).isEqualTo("glass")
        assertThat(touched.pairedAtMs).isEqualTo(1_000L)
    }

    @Test
    fun `touch of an unknown device is a no-op`() {
        // Given an empty table
        // When a non-existent device is touched
        repo.touch("missing", 1L)

        // Then nothing was created
        assertThat(rowCount()).isEqualTo(0)
    }

    @Test
    fun `onCreate provisions exactly one application table`() {
        // Given the helper opened the database for the first time
        repo.writableDatabase

        // Then paired_device exists and the schema carries no other app table
        // (android_metadata is the framework's own, not ours)
        assertThat(tableNames()).contains("paired_device")
        assertThat(tableNames().filterNot { it.startsWith("android_") })
            .containsExactly("paired_device")
    }

    @Test
    fun `the sqlite adapter satisfies the domain port`() {
        // Given the adapter viewed through the domain interface
        val port: DeviceRepository = repo

        // When used through the port
        port.upsert(pairedDevice(id = "d-9", tokenHash = "hash-nine"))

        // Then the persisted state is observable through the same port
        assertThat(port.getByTokenHash("hash-nine")?.deviceId).isEqualTo("d-9")
    }
}
