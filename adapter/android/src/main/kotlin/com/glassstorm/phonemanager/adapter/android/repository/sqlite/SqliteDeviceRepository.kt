package com.glassstorm.phonemanager.adapter.android.repository.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.glassstorm.phonemanager.core.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.core.model.Device

/**
 * SQLite-backed [DeviceRepository], built directly on [SQLiteOpenHelper]
 * (no Room, no SQLDelight).
 *
 * The hub persists only the pairing-token HASH; the token itself never reaches disk.
 */
class SqliteDeviceRepository(
    private val context: Context,
    private val databaseName: String = DEFAULT_DATABASE_NAME,
) : SQLiteOpenHelper(context, databaseName, null, SCHEMA_VERSION),
    DeviceRepository {
    override fun getWritableDatabase(): SQLiteDatabase {
        ensureDatabaseDirectory()
        return super.getWritableDatabase()
    }

    override fun getReadableDatabase(): SQLiteDatabase {
        ensureDatabaseDirectory()
        return super.getReadableDatabase()
    }

    // Robolectric's Context.getDatabasePath() does not mkdirs, so the database file's
    // parent directory must exist before SQLiteDatabase opens it.
    private fun ensureDatabaseDirectory() {
        context.getDatabasePath(databaseName).parentFile?.mkdirs()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_PAIRED_DEVICE)
    }

    // No versioned migration exists: the v1 schema is authoritative, so a version bump
    // rebuilds the table instead of altering it.
    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PAIRED_DEVICE")
        onCreate(db)
    }

    override fun upsert(device: Device) {
        writableDatabase.insertWithOnConflict(
            TABLE_PAIRED_DEVICE,
            null,
            deviceValues(device),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun get(deviceId: String): Device? = queryDevice("$COLUMN_DEVICE_ID = ?", arrayOf(deviceId))

    override fun getByTokenHash(tokenHash: String): Device? = queryDevice("$COLUMN_TOKEN_HASH = ?", arrayOf(tokenHash))

    override fun list(): List<Device> =
        readableDatabase
            .query(
                TABLE_PAIRED_DEVICE,
                null,
                null,
                null,
                null,
                null,
                "$COLUMN_PAIRED_AT_MS ASC, $COLUMN_DEVICE_ID ASC",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(readDevice(cursor))
                }
            }

    override fun touch(
        deviceId: String,
        seenAtMs: Long,
    ) {
        writableDatabase.update(
            TABLE_PAIRED_DEVICE,
            ContentValues().apply { put(COLUMN_LAST_SEEN_MS, seenAtMs) },
            "$COLUMN_DEVICE_ID = ?",
            arrayOf(deviceId),
        )
    }

    override fun delete(deviceId: String) {
        writableDatabase.delete(TABLE_PAIRED_DEVICE, "$COLUMN_DEVICE_ID = ?", arrayOf(deviceId))
    }

    private fun queryDevice(
        selection: String,
        selectionArgs: Array<String>,
    ): Device? =
        readableDatabase
            .query(
                TABLE_PAIRED_DEVICE,
                null,
                selection,
                selectionArgs,
                null,
                null,
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                readDevice(cursor)
            }

    private fun readDevice(cursor: Cursor): Device {
        val lastSeenIndex = cursor.getColumnIndexOrThrow(COLUMN_LAST_SEEN_MS)
        return Device(
            deviceId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DEVICE_ID)),
            deviceName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DEVICE_NAME)),
            role = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ROLE)),
            tokenHash = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TOKEN_HASH)),
            pairedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_PAIRED_AT_MS)),
            lastSeenMs = if (cursor.isNull(lastSeenIndex)) null else cursor.getLong(lastSeenIndex),
        )
    }

    private fun deviceValues(device: Device): ContentValues =
        ContentValues().apply {
            put(COLUMN_DEVICE_ID, device.deviceId)
            put(COLUMN_DEVICE_NAME, device.deviceName)
            put(COLUMN_ROLE, device.role)
            put(COLUMN_TOKEN_HASH, device.tokenHash)
            put(COLUMN_PAIRED_AT_MS, device.pairedAtMs)
            device.lastSeenMs?.let { put(COLUMN_LAST_SEEN_MS, it) } ?: putNull(COLUMN_LAST_SEEN_MS)
        }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val DEFAULT_DATABASE_NAME = "phone_manager.db"

        const val TABLE_PAIRED_DEVICE = "paired_device"

        const val COLUMN_DEVICE_ID = "device_id"
        const val COLUMN_DEVICE_NAME = "device_name"
        const val COLUMN_ROLE = "role"
        const val COLUMN_TOKEN_HASH = "token_hash"
        const val COLUMN_PAIRED_AT_MS = "paired_at_ms"
        const val COLUMN_LAST_SEEN_MS = "last_seen_ms"

        const val CREATE_PAIRED_DEVICE =
            "CREATE TABLE IF NOT EXISTS $TABLE_PAIRED_DEVICE (" +
                "$COLUMN_DEVICE_ID TEXT PRIMARY KEY, " +
                "$COLUMN_DEVICE_NAME TEXT NOT NULL, " +
                "$COLUMN_ROLE TEXT NOT NULL, " +
                "$COLUMN_TOKEN_HASH TEXT NOT NULL, " +
                "$COLUMN_PAIRED_AT_MS INTEGER NOT NULL, " +
                "$COLUMN_LAST_SEEN_MS INTEGER)"
    }
}
