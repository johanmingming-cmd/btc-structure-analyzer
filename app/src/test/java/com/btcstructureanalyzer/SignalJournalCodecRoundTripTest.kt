package com.btcstructureanalyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalJournalCodecRoundTripTest {

    private fun completeRecord(id: String, time: Long, symbol: String) = SignalRecord(
        id = id,
        time = time,
        symbol = symbol,
        direction = "LONG",
        price = 65000.125,
        entryLow = 64980.125,
        entryHigh = 65020.125,
        stopLoss = 64800.0,
        takeProfit1 = 65200.0,
        takeProfit2 = 65400.0,
        score = 87,
        supports = listOf(64500.5, 64000.25),
        resistances = listOf(65500.75, 66000.5),
        price10m = 65010.0,
        price15m = 65020.0,
        price30m = 65030.0,
        price60m = 65040.0,
        result10m = "ARAH_POSITIF",
        result15m = "ARAH_NETRAL",
        result30m = "ARAH_NEGATIF",
        result60m = "PENDING",
        entryStatus = "MASIH DALAM RANGE",
        evaluationTime10m = time + 10 * 60_000L,
        evaluationTime15m = time + 15 * 60_000L,
        evaluationTime30m = time + 30 * 60_000L,
        evaluationTime60m = time + 60 * 60_000L,
        priceSource10m = "SOURCE_10",
        priceSource15m = "SOURCE_15",
        priceSource30m = "SOURCE_30",
        priceSource60m = "SOURCE_60",
        tradeOutcome = "TP1_TERCAPAI",
        tradeOutcomeTime = time + 45 * 60_000L,
        cohort = "PROSPECTIVE_V1"
    )

    @Test
    fun emptyJournalRoundTrips() {
        assertEquals(emptyList<SignalRecord>(), SignalJournal.parseRecords(SignalJournal.serializeRecords(emptyList())))
    }

    @Test
    fun everySignalRecordFieldRoundTripsExactly() {
        val original = listOf(completeRecord("full-1", 1_700_000_000_000L, "BTCUSDT"))

        val restored = SignalJournal.parseRecords(SignalJournal.serializeRecords(original))

        assertEquals(original, restored)
    }

    @Test
    fun duplicateIdsAndOriginalOrderSurviveWhenTimestampsTie() {
        val first = completeRecord("duplicate-id", 1_700_000_000_000L, "BTCUSDT")
        val second = completeRecord("duplicate-id", 1_700_000_000_000L, "ETHUSDT")
            .copy(direction = "SHORT", cohort = "LEGACY")
        val third = completeRecord("third", 1_700_000_000_000L, "SOLUSDT")

        val restored = SignalJournal.parseRecords(SignalJournal.serializeRecords(listOf(first, second, third)))

        assertEquals(listOf(first, second, third), restored)
        assertEquals(3, restored.size)
        assertEquals(listOf("BTCUSDT", "ETHUSDT", "SOLUSDT"), restored.map { it.symbol })
    }

    @Test
    fun nullableEvaluationFieldsAndLegacyDefaultsSurviveRoundTrip() {
        val legacy = completeRecord("legacy-1", 1_700_000_000_000L, "BTCUSDT").copy(
            price10m = null,
            price15m = null,
            price30m = null,
            price60m = null,
            evaluationTime10m = null,
            evaluationTime15m = null,
            evaluationTime30m = null,
            evaluationTime60m = null,
            priceSource10m = null,
            priceSource15m = null,
            priceSource30m = null,
            priceSource60m = null,
            tradeOutcomeTime = null,
            cohort = "LEGACY"
        )

        assertEquals(listOf(legacy), SignalJournal.parseRecords(SignalJournal.serializeRecords(listOf(legacy))))
    }
}


    @Test
    fun olderJournalSchemaWithoutNewerOptionalFieldsUsesSafeLegacyDefaults() {
        // Represents a record written before horizon evaluation, trade outcome, and cohort fields existed.
        val oldRecord = """[{
            "id":"old-1",
            "time":1700000000000,
            "symbol":"BTCUSDT",
            "direction":"LONG",
            "price":65000.0,
            "entryLow":64980.0,
            "entryHigh":65020.0,
            "stopLoss":64800.0,
            "takeProfit1":65200.0,
            "takeProfit2":65400.0,
            "score":80,
            "supports":[64500.0],
            "resistances":[65500.0]
        }]"""

        val restored = SignalJournal.parseRecords(oldRecord).single()

        assertEquals("old-1", restored.id)
        assertEquals(1700000000000L, restored.time)
        assertEquals(listOf(64500.0), restored.supports)
        assertEquals(listOf(65500.0), restored.resistances)
        assertEquals(null, restored.price10m)
        assertEquals(null, restored.price15m)
        assertEquals(null, restored.price30m)
        assertEquals(null, restored.price60m)
        assertEquals("PENDING", restored.result10m)
        assertEquals("PENDING", restored.result15m)
        assertEquals("PENDING", restored.result30m)
        assertEquals("PENDING", restored.result60m)
        assertEquals("BELUM DICEK", restored.entryStatus)
        assertEquals(null, restored.evaluationTime10m)
        assertEquals(null, restored.evaluationTime15m)
        assertEquals(null, restored.evaluationTime30m)
        assertEquals(null, restored.evaluationTime60m)
        assertEquals(null, restored.priceSource10m)
        assertEquals(null, restored.priceSource15m)
        assertEquals(null, restored.priceSource30m)
        assertEquals(null, restored.priceSource60m)
        assertEquals("PENDING", restored.tradeOutcome)
        assertEquals(null, restored.tradeOutcomeTime)
        assertEquals("LEGACY", restored.cohort)

        // Re-encoding must retain the record and its original core identity/price data.
        val roundTripped = SignalJournal.parseRecords(SignalJournal.serializeRecords(listOf(restored))).single()
        assertEquals(restored, roundTripped)
    }


    @Test
    fun malformedIntegerFieldsAreRejectedInsteadOfSilentlyCoerced() {
        val valid = """[{
            "id":"strict-1","time":1700000000000,"symbol":"BTCUSDT","direction":"LONG",
            "price":65000.0,"entryLow":64980.0,"entryHigh":65020.0,
            "stopLoss":64800.0,"takeProfit1":65200.0,"takeProfit2":65400.0,
            "score":80,"supports":[],"resistances":[]
        }]"""
        val malformed = listOf(
            valid.replace("\"time\":1700000000000", "\"time\":\"1700000000000\""),
            valid.replace("\"score\":80", "\"score\":\"80\""),
            valid.replace("\"score\":80", "\"score\":80.5"),
            valid.replace("\"supports\":[]", "\"supports\":[],\"evaluationTime10m\":\"1700000600000\"")
        )

        malformed.forEach { raw ->
            try {
                SignalJournal.parseRecords(raw)
                throw AssertionError("Malformed integer field must be rejected: $raw")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("existing data was left untouched"))
            }
        }
    }
