package com.btcstructureanalyzer

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Isolated SQLite journal store. It is deliberately not wired into SignalJournal yet.
 * The SharedPreferences JSON remains the migration source and is never modified here.
 */
internal class SignalJournalDatabase(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    enum class TestFault { NONE, THROW_AFTER_ROWS }

    data class MigrationResult(val migrated: Boolean, val recordCount: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE signal_records (
                ordinal INTEGER PRIMARY KEY NOT NULL,
                record_id TEXT NOT NULL,
                payload_json TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_signal_records_record_id ON signal_records(record_id)")
        db.execSQL(
            """CREATE TABLE journal_meta (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No schema upgrades are defined until a separately tested version is introduced.
        error("Unsupported journal database upgrade: $oldVersion -> $newVersion")
    }

    /**
     * Caller must read and strictly parse the original SharedPreferences value before calling.
     * All rows and the completion marker are committed atomically.
     */
    @JvmOverloads
    fun migrateIfNeeded(
        records: List<SignalRecord>,
        testFault: TestFault = TestFault.NONE
    ): MigrationResult {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existingMarker = migrationMarker(db)
            if (existingMarker != null) {
                val markerParts = existingMarker.split(":")
                check(markerParts.size == 2 && markerParts[0].toIntOrNull() == DATABASE_VERSION) {
                    "Journal migration marker is malformed or belongs to an unsupported schema."
                }
                val markedCount = markerParts[1].toIntOrNull()
                    ?: error("Journal migration marker has an invalid record count.")
                val actualCount = rowCount(db)
                check(markedCount == actualCount) {
                    "Journal migration marker count mismatch: marker=$markedCount, database=$actualCount."
                }
                validateStoredRows(db, expectedCount = actualCount)
                db.setTransactionSuccessful()
                return MigrationResult(migrated = false, recordCount = actualCount)
            }

            records.forEachIndexed { index, record ->
                val values = android.content.ContentValues().apply {
                    put("ordinal", index)
                    put("record_id", record.id)
                    put("payload_json", SignalJournal.serializeRecords(listOf(record)))
                }
                db.insertOrThrow("signal_records", null, values)
            }

            if (testFault == TestFault.THROW_AFTER_ROWS) {
                throw IllegalStateException("Injected failure after row inserts for rollback test.")
            }

            val inserted = rowCount(db)
            check(inserted == records.size) {
                "Journal migration row count mismatch: expected ${records.size}, found $inserted."
            }
            validateStoredRows(db, expectedCount = inserted)
            val marker = android.content.ContentValues().apply {
                put("key", MIGRATION_MARKER)
                put("value", "$DATABASE_VERSION:$inserted")
            }
            db.insertOrThrow("journal_meta", null, marker)
            db.setTransactionSuccessful()
            return MigrationResult(migrated = true, recordCount = inserted)
        } finally {
            db.endTransaction()
        }
    }

    fun readAll(): List<SignalRecord> = validateStoredRows(readableDatabase)

    /**
     * A completion marker is trusted only when every stored row decodes to one record,
     * record_id matches the payload, and ordinals are contiguous from zero.
     */
    fun isMigrated(): Boolean = runCatching {
        val db = readableDatabase
        val marker = migrationMarker(db) ?: return false
        val parts = marker.split(":")
        if (parts.size != 2 || parts[0].toIntOrNull() != DATABASE_VERSION) return false
        val markedCount = parts[1].toIntOrNull() ?: return false
        if (markedCount < 0 || markedCount != rowCount(db)) return false
        validateStoredRows(db, expectedCount = markedCount)
        true
    }.getOrDefault(false)

    private fun validateStoredRows(
        db: SQLiteDatabase,
        expectedCount: Int? = null
    ): List<SignalRecord> {
        val result = mutableListOf<SignalRecord>()
        db.rawQuery(
            "SELECT ordinal, record_id, payload_json FROM signal_records ORDER BY ordinal ASC",
            null
        ).use { cursor ->
            var expectedOrdinal = 0
            while (cursor.moveToNext()) {
                val ordinal = cursor.getInt(0)
                check(ordinal == expectedOrdinal) {
                    "Stored journal ordinals are not contiguous: expected $expectedOrdinal, found $ordinal."
                }
                val recordId = cursor.getString(1)
                val decoded = SignalJournal.parseRecords(cursor.getString(2))
                check(decoded.size == 1) { "Stored journal row must contain exactly one record." }
                val record = decoded.single()
                check(record.id == recordId) {
                    "Stored journal record_id does not match its payload at ordinal $ordinal."
                }
                result += record
                expectedOrdinal++
            }
            if (expectedCount != null) {
                check(expectedOrdinal == expectedCount) {
                    "Stored journal row count mismatch: expected $expectedCount, found $expectedOrdinal."
                }
            }
        }
        return result
    }

    fun integrityCheck(): String =
        readableDatabase.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            check(cursor.moveToFirst()) { "SQLite integrity_check returned no result." }
            cursor.getString(0)
        }

    private fun migrationMarker(db: SQLiteDatabase): String? =
        db.rawQuery(
            "SELECT value FROM journal_meta WHERE key = ?",
            arrayOf(MIGRATION_MARKER)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun rowCount(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT COUNT(*) FROM signal_records", null).use {
            check(it.moveToFirst())
            it.getInt(0)
        }

    companion object {
        /**
         * Distinguishes an absent legacy key from a present-but-corrupt backup.
         * Malformed and non-string values stop migration instead of becoming an empty journal.
         */
        fun readLegacyRecords(preferences: SharedPreferences, key: String): List<SignalRecord> {
            if (!preferences.contains(key)) return emptyList()
            val raw = try {
                preferences.getString(key, null)
            } catch (error: ClassCastException) {
                throw IllegalStateException(
                    "Legacy signal journal exists but is not stored as a string; backup was left untouched.",
                    error
                )
            } ?: throw IllegalStateException(
                "Legacy signal journal key exists but has no readable string value; backup was left untouched."
            )
            return SignalJournal.parseRecords(raw)
        }

        const val DATABASE_NAME = "prediction_lab_journal.db"
        private const val DATABASE_VERSION = 1
        private const val MIGRATION_MARKER = "shared_preferences_json_v1"
    }
}
