package com.btcstructureanalyzer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Infrastructure smoke tests only. These do not exercise the production journal or perform migration.
 * They establish that Android's on-device SQLite transaction behavior is available in CI before
 * a production migration implementation is introduced.
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
}
