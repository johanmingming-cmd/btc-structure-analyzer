package com.btcstructureanalyzer

import android.content.Context
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

    fun readAll(): List<SignalRecord> {
        val result = mutableListOf<SignalRecord>()
        readableDatabase.rawQuery(
            "SELECT payload_json FROM signal_records ORDER BY ordinal ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val decoded = SignalJournal.parseRecords(cursor.getString(0))
                check(decoded.size == 1) { "Stored journal row must contain exactly one record." }
                result += decoded.single()
            }
        }
        return result
    }

    fun isMigrated(): Boolean = migrationMarker(readableDatabase) != null

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
        const val DATABASE_NAME = "prediction_lab_journal.db"
        private const val DATABASE_VERSION = 1
        private const val MIGRATION_MARKER = "shared_preferences_json_v1"
    }
}
