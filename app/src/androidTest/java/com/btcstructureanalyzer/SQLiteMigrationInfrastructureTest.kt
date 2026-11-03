package com.btcstructureanalyzer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for SQLite transaction behavior, isolated journal migration,
 * backup-source validation, and detection of incomplete or corrupted stored rows.
 */
@RunWith(AndroidJUnit4::class)
class SQLiteMigrationInfrastructureTest {

    private class ProbeDatabase(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE probe_records (ordinal INTEGER PRIMARY KEY NOT NULL, payload TEXT NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Test
    fun uncommittedTransactionRollsBackAfterDatabaseReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration_probe_rollback.db"
        context.deleteDatabase(name)

        ProbeDatabase(context, name).use { helper ->
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.execSQL(
                    "INSERT INTO probe_records (ordinal, payload) VALUES (?, ?)",
                    arrayOf(0, "must-rollback")
                )
                // Deliberately do not mark the transaction successful.
            } finally {
                db.endTransaction()
            }
        }

        ProbeDatabase(context, name).use { helper ->
            helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM probe_records", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun committedTransactionSurvivesDatabaseReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration_probe_commit.db"
        context.deleteDatabase(name)

        ProbeDatabase(context, name).use { helper ->
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.execSQL(
                    "INSERT INTO probe_records (ordinal, payload) VALUES (?, ?)",
                    arrayOf(0, "committed")
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        ProbeDatabase(context, name).use { helper ->
            helper.readableDatabase.rawQuery(
                "SELECT payload FROM probe_records ORDER BY ordinal ASC",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("committed", cursor.getString(0))
                assertEquals(false, cursor.moveToNext())
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun sqliteProbeDoesNotModifySharedPreferencesBackup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("btc_structure", Context.MODE_PRIVATE)
        val key = "migration_probe_backup"
        val original = """[{"id":"backup-only","symbol":"BTCUSDT","price":65000}]"""
        prefs.edit().putString(key, original).commit()

        val name = "migration_probe_backup.db"
        context.deleteDatabase(name)
        ProbeDatabase(context, name).use { helper ->
            helper.writableDatabase.execSQL(
                "INSERT INTO probe_records (ordinal, payload) VALUES (?, ?)",
                arrayOf(0, "separate-storage")
            )
        }

        assertEquals(original, prefs.getString(key, null))
        prefs.edit().remove(key).commit()
        context.deleteDatabase(name)
    }

    private fun sampleRecord(id: String, symbol: String, time: Long) = SignalRecord(
        id = id, time = time, symbol = symbol, direction = "LONG",
        price = 65000.0, entryLow = 64900.0, entryHigh = 65100.0,
        stopLoss = 64000.0, takeProfit1 = 66000.0, takeProfit2 = 67000.0,
        score = 70, supports = listOf(64000.0), resistances = listOf(66000.0)
    )

    @Test
    fun actualMigrationPreservesRowsOrderDuplicateIdsAndBackup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("btc_structure", Context.MODE_PRIVATE)
        val backupKey = "signal_journal_v1"
        val records = listOf(
            sampleRecord("same-id", "BTCUSDT", 1000L),
            sampleRecord("same-id", "ETHUSDT", 1000L),
            sampleRecord("third", "SOLUSDT", 2000L)
        )
        val rawBackup = SignalJournal.serializeRecords(records)
        prefs.edit().putString(backupKey, rawBackup).commit()
        val name = "journal_migration_actual.db"
        context.deleteDatabase(name)

        SignalJournalDatabase(context, name).use { store ->
            val result = store.migrateIfNeeded(SignalJournal.parseRecords(prefs.getString(backupKey, null)!!))
            assertTrue(result.migrated)
            assertEquals(3, result.recordCount)
            assertEquals(records.map { SignalJournal.serializeRecords(listOf(it)) }, store.readAll().map { SignalJournal.serializeRecords(listOf(it)) })
            assertEquals("ok", store.integrityCheck())
            assertTrue(store.isMigrated())
        }

        assertEquals(rawBackup, prefs.getString(backupKey, null))
        prefs.edit().remove(backupKey).commit()
        context.deleteDatabase(name)
    }

    @Test
    fun actualMigrationIsIdempotentAfterDatabaseReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "journal_migration_idempotent.db"
        context.deleteDatabase(name)
        val records = listOf(sampleRecord("one", "BTCUSDT", 1000L), sampleRecord("two", "ETHUSDT", 2000L))

        SignalJournalDatabase(context, name).use { store ->
            assertTrue(store.migrateIfNeeded(records).migrated)
        }
        SignalJournalDatabase(context, name).use { store ->
            val second = store.migrateIfNeeded(records)
            assertEquals(false, second.migrated)
            assertEquals(2, second.recordCount)
            assertEquals(records, store.readAll())
            assertEquals("ok", store.integrityCheck())
        }
        context.deleteDatabase(name)
    }

    @Test
    fun failedMigrationRollsBackRowsAndMarkerThenRetrySucceeds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "journal_migration_retry.db"
        context.deleteDatabase(name)
        val records = listOf(sampleRecord("one", "BTCUSDT", 1000L), sampleRecord("two", "ETHUSDT", 2000L))

        SignalJournalDatabase(context, name).use { store ->
            try {
                store.migrateIfNeeded(records, SignalJournalDatabase.TestFault.THROW_AFTER_ROWS)
                throw AssertionError("Injected migration failure was not thrown.")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("Injected failure"))
            }
            assertEquals(false, store.isMigrated())
            assertEquals(emptyList<SignalRecord>(), store.readAll())
        }

        SignalJournalDatabase(context, name).use { store ->
            val retry = store.migrateIfNeeded(records)
            assertTrue(retry.migrated)
            assertEquals(2, retry.recordCount)
            assertEquals(records, store.readAll())
            assertTrue(store.isMigrated())
        }
        context.deleteDatabase(name)
    }


    @Test
    fun migrationPreservesMoreThanTwoThousandSourceRecordsWithoutTruncation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("btc_structure", Context.MODE_PRIVATE)
        val backupKey = "signal_journal_v1"
        val sourceRecords = (0..2_000).map { index ->
            sampleRecord("id-$index", "COIN${index}USDT", 10_000L + index)
        }
        val rawBackup = JSONArray().apply {
            sourceRecords.forEach { record ->
                put(JSONArray(SignalJournal.serializeRecords(listOf(record))).getJSONObject(0))
            }
        }.toString()
        prefs.edit().putString(backupKey, rawBackup).commit()

        val name = "journal_migration_over_limit.db"
        context.deleteDatabase(name)
        SignalJournalDatabase(context, name).use { store ->
            val parsed = SignalJournal.parseRecords(prefs.getString(backupKey, null)!!)
            assertEquals(2_001, parsed.size)
            val result = store.migrateIfNeeded(parsed)
            assertTrue(result.migrated)
            assertEquals(2_001, result.recordCount)
            val restored = store.readAll()
            assertEquals(2_001, restored.size)
            assertEquals("id-0", restored.first().id)
            assertEquals("id-2000", restored.last().id)
        }
        assertEquals(rawBackup, prefs.getString(backupKey, null))
        prefs.edit().remove(backupKey).commit()
        context.deleteDatabase(name)
    }


    @Test
    fun malformedLegacyBackupIsRejectedWithoutChangingBackupOrCreatingMigrationMarker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("btc_structure", Context.MODE_PRIVATE)
        val backupKey = "signal_journal_v1"
        val malformedBackup = "[{"
        prefs.edit().putString(backupKey, malformedBackup).commit()

        val name = "journal_migration_malformed_backup.db"
        context.deleteDatabase(name)
        SignalJournalDatabase(context, name).use { store ->
            try {
                val parsed = SignalJournal.parseRecords(prefs.getString(backupKey, null)!!)
                store.migrateIfNeeded(parsed)
                throw AssertionError("Malformed legacy backup must not be migrated.")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("existing data was left untouched"))
            }
            assertEquals(false, store.isMigrated())
            assertEquals(emptyList<SignalRecord>(), store.readAll())
        }

        assertEquals(malformedBackup, prefs.getString(backupKey, null))
        prefs.edit().remove(backupKey).commit()
        context.deleteDatabase(name)
    }


    @Test
    fun emptyJournalMigrationIsMarkedAndIdempotent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "journal_migration_empty.db"
        context.deleteDatabase(name)

        SignalJournalDatabase(context, name).use { store ->
            val first = store.migrateIfNeeded(emptyList())
            assertTrue(first.migrated)
            assertEquals(0, first.recordCount)
            assertTrue(store.isMigrated())
        }
        SignalJournalDatabase(context, name).use { store ->
            val second = store.migrateIfNeeded(emptyList())
            assertEquals(false, second.migrated)
            assertEquals(0, second.recordCount)
            assertEquals(emptyList<SignalRecord>(), store.readAll())
            assertEquals("ok", store.integrityCheck())
        }
        context.deleteDatabase(name)
    }

    @Test
    fun unsupportedDatabaseUpgradeFailsWithoutDroppingLegacyData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "journal_unsupported_upgrade.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacyDb ->
            legacyDb.execSQL("CREATE TABLE legacy_data (value TEXT NOT NULL)")
            legacyDb.execSQL(
                "INSERT INTO legacy_data (value) VALUES (?)",
                arrayOf("must-remain")
            )
            legacyDb.version = 0
        }

        try {
            SignalJournalDatabase(context, name).use { it.writableDatabase }
            throw AssertionError("Unsupported schema upgrade must be rejected.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Unsupported journal database upgrade"))
        }

        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacyDb ->
            legacyDb.rawQuery("SELECT value FROM legacy_data", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("must-remain", cursor.getString(0))
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun absentLegacyKeyMeansEmptyButMalformedOrWrongTypeBackupFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("btc_structure", Context.MODE_PRIVATE)
        val absentKey = "test_absent_legacy_journal"
        val malformedKey = "test_malformed_legacy_journal"
        val wrongTypeKey = "test_wrong_type_legacy_journal"

        prefs.edit().remove(absentKey).remove(malformedKey).remove(wrongTypeKey).commit()
        assertEquals(emptyList<SignalRecord>(), SignalJournalDatabase.readLegacyRecords(prefs, absentKey))

        val malformed = "[{"
        prefs.edit().putString(malformedKey, malformed).commit()
        try {
            SignalJournalDatabase.readLegacyRecords(prefs, malformedKey)
            throw AssertionError("A present malformed backup must not be treated as empty.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("existing data was left untouched"))
        }
        assertEquals(malformed, prefs.getString(malformedKey, null))

        prefs.edit().putInt(wrongTypeKey, 42).commit()
        try {
            SignalJournalDatabase.readLegacyRecords(prefs, wrongTypeKey)
            throw AssertionError("A present non-string backup must not be treated as empty.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not stored as a string"))
        }
        assertEquals(42, prefs.getInt(wrongTypeKey, -1))
        prefs.edit().remove(malformedKey).remove(wrongTypeKey).commit()
    }

    @Test
    fun migrationMarkerDoesNotTrustCorruptedPayloadOrMismatchedRecordId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "journal_migration_corrupt_payload.db"
        context.deleteDatabase(name)
        val record = sampleRecord("expected-id", "BTCUSDT", 1000L)

        SignalJournalDatabase(context, name).use { store ->
            store.migrateIfNeeded(listOf(record))
            store.writableDatabase.execSQL(
                "UPDATE signal_records SET payload_json = ? WHERE ordinal = 0",
                arrayOf("[{broken]")
            )
            assertEquals(false, store.isMigrated())
            try {
                store.migrateIfNeeded(listOf(record))
                throw AssertionError("A completion marker with corrupted payload must not be trusted.")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("could not be read"))
            }
            try {
                store.readAll()
                throw AssertionError("Corrupted payload must not be returned.")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("could not be read"))
            }
        }
        context.deleteDatabase(name)

        val mismatchName = "journal_migration_record_id_mismatch.db"
        context.deleteDatabase(mismatchName)
        SignalJournalDatabase(context, mismatchName).use { store ->
            store.migrateIfNeeded(listOf(record))
            store.writableDatabase.execSQL(
                "UPDATE signal_records SET record_id = ? WHERE ordinal = 0",
                arrayOf("different-id")
            )
            assertEquals(false, store.isMigrated())
            try {
                store.readAll()
                throw AssertionError("Mismatched record_id must not be returned.")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("record_id does not match"))
            }
        }
        context.deleteDatabase(mismatchName)
    }

    @Test
    fun migrationMarkerDoesNotTrustNonContiguousOrdinals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "journal_migration_ordinal_gap.db"
        context.deleteDatabase(name)
        val records = listOf(
            sampleRecord("first", "BTCUSDT", 1000L),
            sampleRecord("second", "ETHUSDT", 2000L)
        )

        SignalJournalDatabase(context, name).use { store ->
            store.migrateIfNeeded(records)
            store.writableDatabase.execSQL("DELETE FROM signal_records WHERE ordinal = 0")
            store.writableDatabase.execSQL(
                "UPDATE journal_meta SET value = ? WHERE key = ?",
                arrayOf("1:1", "shared_preferences_json_v1")
            )
            assertEquals(false, store.isMigrated())
            try {
                store.readAll()
                throw AssertionError("A non-contiguous ordinal sequence must not be returned.")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("ordinals are not contiguous"))
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun inconsistentMigrationMarkerIsRejectedInsteadOfTrusted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "journal_migration_bad_marker.db"
        context.deleteDatabase(name)
        val records = listOf(sampleRecord("one", "BTCUSDT", 1000L))

        SignalJournalDatabase(context, name).use { store ->
            store.writableDatabase.execSQL(
                "INSERT INTO journal_meta (key, value) VALUES (?, ?)",
                arrayOf("shared_preferences_json_v1", "1:1")
            )
            try {
                store.migrateIfNeeded(records)
                throw AssertionError("Marker count mismatch must be rejected.")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("marker count mismatch"))
            }
            assertEquals(false, store.isMigrated())
            assertEquals(emptyList<SignalRecord>(), store.readAll())
        }
        context.deleteDatabase(name)
    }

}
