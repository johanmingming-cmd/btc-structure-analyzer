package com.btcstructureanalyzer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalJournalCodecTest {
    private fun record(id: String = "signal-1") = SignalRecord(
        id = id, time = 1_700_000_000_000L, symbol = "BTCUSDT",
        direction = "LONG", price = 65_000.0, score = 72,
        supports = listOf(64_500.0), resistances = listOf(65_500.0),
        cohort = "PROSPECTIVE_V1"
    )

    @Test fun newRecordsDoNotGenerateEntryOrTpSlFields() {
        val raw = SignalJournal.serializeRecords(listOf(record()))
        val json = JSONObject(JSONArray(raw).getJSONObject(0).toString())
        listOf("entryLow", "entryHigh", "stopLoss", "takeProfit1", "takeProfit2").forEach {
            assertFalse("new signal must not contain $it", json.has(it))
        }
        assertEquals(record(), SignalJournal.parseRecords(raw).single())
    }

    @Test fun forwardTestOneThreeAndSixHourOutcomesRoundTrip() {
        val original = record().copy(
            price60m = 65_100.0, result60m = "ARAH_POSITIF",
            evaluationTime60m = 1_700_003_600_000L,
            price180m = 65_200.0, result180m = "ARAH_POSITIF",
            evaluationTime180m = 1_700_010_800_000L,
            price360m = 64_900.0, result360m = "ARAH_NEGATIF",
            evaluationTime360m = 1_700_021_600_000L,
            priceSource180m = "BINANCE_SPOT_1M_CANDLE_CLOSE",
            priceSource360m = "BINANCE_SPOT_1M_CANDLE_CLOSE"
        )
        val restored = SignalJournal.parseRecords(
            SignalJournal.serializeRecords(listOf(original))
        ).single()
        assertEquals(original, restored)
    }

    @Test fun oldRecordsWithoutTpSlFieldsRemainReadable() {
        val old = """[{"id":"old-1","time":1700000000000,"symbol":"BTCUSDT","direction":"LONG","price":65000.0,"score":80,"supports":[64500.0],"resistances":[65500.0]}]"""
        val restored = SignalJournal.parseRecords(old).single()
        assertEquals("old-1", restored.id)
        assertNull(restored.entryLow)
        assertNull(restored.stopLoss)
        assertEquals("LEGACY", restored.cohort)
        assertEquals(restored, SignalJournal.parseRecords(SignalJournal.serializeRecords(listOf(restored))).single())
    }

    @Test fun oldTpSlValuesAreOptionalAndPreservedOnlyAsLegacyData() {
        val old = """[{"id":"old-2","time":1700000000000,"symbol":"ETHUSDT","direction":"SHORT","price":3000.0,"score":75,"entryLow":2990.0,"entryHigh":3010.0,"stopLoss":3050.0,"takeProfit1":2900.0,"takeProfit2":2800.0}]"""
        val restored = SignalJournal.parseRecords(old).single()
        assertEquals(3050.0, restored.stopLoss!!, 0.0)
        assertEquals(2800.0, SignalJournal.parseRecords(SignalJournal.serializeRecords(listOf(restored))).single().takeProfit2!!, 0.0)
    }

    @Test fun malformedLegacyTpSlValuesDoNotBlockDirectionHistory() {
        val raw = """[{"id":"old-bad-tpsl","time":1700000000000,"symbol":"BTCUSDT","direction":"LONG","price":65000.0,"score":80,"entryLow":"bad","entryHigh":-1,"stopLoss":null,"takeProfit1":"unknown","takeProfit2":0}]"""
        val restored = SignalJournal.parseRecords(raw).single()
        assertEquals("old-bad-tpsl", restored.id)
        assertNull(restored.entryLow)
        assertNull(restored.entryHigh)
        assertNull(restored.stopLoss)
        assertNull(restored.takeProfit1)
        assertNull(restored.takeProfit2)
    }

    @Test fun malformedJournalFailsClosedInsteadOfBecomingEmpty() {
        assertThrows(IllegalStateException::class.java) { SignalJournal.parseRecords("[{\"id\":") }
        assertThrows(IllegalStateException::class.java) { SignalJournal.parseRecords("[null]") }
        assertThrows(IllegalStateException::class.java) {
            SignalJournal.parseRecords("""[{"id":"bad","time":1,"symbol":"BTCUSDT","direction":"BUY","price":1.0,"score":1}]""")
        }
        assertThrows(IllegalStateException::class.java) {
            SignalJournal.parseRecords("""[{"id":"bad","time":1,"symbol":"BTCUSDT","direction":"LONG","price":"1.0","score":1}]""")
        }
    }

    @Test fun recordLimitRemainsFiveHundred() {
        val records = (1..501).map { record("id-$it") }
        assertEquals(500, SignalJournal.parseRecords(SignalJournal.serializeRecords(records)).size)
    }

    @Test fun missingEvaluationMetadataDefaultsSafely() {
        val restored = SignalJournal.parseRecords(
            """[{"id":"old-3","time":1700000000000,"symbol":"SOLUSDT","direction":"LONG","price":150.0,"score":60}]"""
        ).single()
        assertEquals("PENDING", restored.result10m)
        assertNull(restored.price10m)
        assertNull(restored.evaluationTime10m)
        assertNull(restored.priceSource10m)
    }
}
