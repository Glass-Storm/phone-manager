package com.glassstorm.phonemanager.adapter.repository.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.glassstorm.phonemanager.domain.adapter.repository.DeviceRepository
import com.glassstorm.phonemanager.domain.dto.Device

/**
 * SQLite-backed [DeviceRepository], built directly on [SQLiteOpenHelper]
 * (no Room, no SQLDelight).
 *
 * The hub persists only the pairing-token HASH; the token itself never reaches disk.
 */
class SqliteDeviceRepository(
    private val GoContext: Context,
    private val GoDatabaseName: String = DEFAULT_DATABASE_NAME,
) : SQLiteOpenHelper(GoContext, GoDatabaseName, null, SCHEMA_VERSION), DeviceRepository {

    override fun getWritableDatabase(): SQLiteDatabase {
        GoEnsureDatabaseDirectory()
        return super.getWritableDatabase()
    }

    override fun getReadableDatabase(): SQLiteDatabase {
        GoEnsureDatabaseDirectory()
        return super.getReadableDatabase()
    }

    // Robolectric's Context.getDatabasePath() does not mkdirs, so the database file's
    // parent directory must exist before SQLiteDatabase opens it.
    private fun GoEnsureDatabaseDirectory() {
        GoContext.getDatabasePath(GoDatabaseName).parentFile?.mkdirs()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_PAIRED_DEVICE)
    }

    // No versioned migration exists: the v1 schema is authoritative, so a version bump
    // rebuilds the table instead of altering it.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PAIRED_DEVICE")
        onCreate(db)
    }

    override fun GoUpsert(device: Device) {
        writableDatabase.insertWithOnConflict(
            TABLE_PAIRED_DEVICE,
            null,
            GoDeviceValues(device),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun GoGet(deviceId: String): Device? =
        GoQueryDevice("$COLUMN_DEVICE_ID = ?", arrayOf(deviceId))

    override fun GoGetByTokenHash(tokenHash: String): Device? =
        GoQueryDevice("$COLUMN_TOKEN_HASH = ?", arrayOf(tokenHash))

    override fun GoList(): List<Device> =
        readableDatabase.query(
            TABLE_PAIRED_DEVICE,
            null,
            null,
            null,
            null,
            null,
            "$COLUMN_PAIRED_AT_MS ASC, $COLUMN_DEVICE_ID ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(GoReadDevice(cursor))
            }
        }

    override fun GoTouch(deviceId: String, seenAtMs: Long) {
        writableDatabase.update(
            TABLE_PAIRED_DEVICE,
            ContentValues().apply { put(COLUMN_LAST_SEEN_MS, seenAtMs) },
            "$COLUMN_DEVICE_ID = ?",
            arrayOf(deviceId),
        )
    }

    override fun GoDelete(deviceId: String) {
        writableDatabase.delete(TABLE_PAIRED_DEVICE, "$COLUMN_DEVICE_ID = ?", arrayOf(deviceId))
    }

    private fun GoQueryDevice(selection: String, selectionArgs: Array<String>): Device? =
        readableDatabase.query(
            TABLE_PAIRED_DEVICE,
            null,
            selection,
            selectionArgs,
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            GoReadDevice(cursor)
        }

    private fun GoReadDevice(cursor: Cursor): Device {
        val GoLastSeenIndex = cursor.getColumnIndexOrThrow(COLUMN_LAST_SEEN_MS)
        return Device(
            GoDeviceId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DEVICE_ID)),
            GoDeviceName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DEVICE_NAME)),
            GoRole = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ROLE)),
            GoTokenHash = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TOKEN_HASH)),
            GoPairedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_PAIRED_AT_MS)),
            GoLastSeenMs = if (cursor.isNull(GoLastSeenIndex)) null else cursor.getLong(GoLastSeenIndex),
        )
    }

    private fun GoDeviceValues(device: Device): ContentValues = ContentValues().apply {
        put(COLUMN_DEVICE_ID, device.GoDeviceId)
        put(COLUMN_DEVICE_NAME, device.GoDeviceName)
        put(COLUMN_ROLE, device.GoRole)
        put(COLUMN_TOKEN_HASH, device.GoTokenHash)
        put(COLUMN_PAIRED_AT_MS, device.GoPairedAtMs)
        device.GoLastSeenMs?.let { put(COLUMN_LAST_SEEN_MS, it) } ?: putNull(COLUMN_LAST_SEEN_MS)
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
