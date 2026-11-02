package com.btcstructureanalyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SignalJournalStorageSafetyTest {
    @Test
    fun legacyJsonWithoutCohortRemainsLegacy() {
        val records = SignalJournal.parseRecords(
            """[{"id":"old-1","time":1000,"symbol":"BTCUSDT","direction":"LONG","price":65000,"entryLow":64900,"entryHigh":65100,"stopLoss":64000,"takeProfit1":66000,"takeProfit2":67000,"score":70}]"""
        )

        assertEquals(1, records.size)
        assertEquals("old-1", records.single().id)
        assertEquals("LEGACY", records.single().cohort)
        assertEquals("PENDING", records.single().result10m)
        assertEquals(null, records.single().price10m)
    }

    @Test
    fun prospectiveJsonPreservesEvaluationMetadata() {
        val records = SignalJournal.parseRecords(
            """[{"id":"new-1","time":1000,"symbol":"ETHUSDT","direction":"SHORT","price":3000,"entryLow":2990,"entryHigh":3010,"stopLoss":3050,"takeProfit1":2900,"takeProfit2":2800,"score":75,"price10m":2980,"result10m":"ARAH_POSITIF","evaluationTime10m":601000,"priceSource10m":"BINANCE_SPOT_1M_CANDLE_CLOSE","cohort":"PROSPECTIVE_V1"}]"""
        )

        val record = records.single()
        assertEquals("PROSPECTIVE_V1", record.cohort)
        assertEquals(2980.0, record.price10m!!, 0.0)
        assertEquals("ARAH_POSITIF", record.result10m)
        assertEquals(601000L, record.evaluationTime10m)
        assertEquals("BINANCE_SPOT_1M_CANDLE_CLOSE", record.priceSource10m)
    }

    @Test
    fun malformedJournalJsonThrowsInsteadOfReturningEmptyList() {
        assertThrows(IllegalStateException::class.java) {
            SignalJournal.parseRecords("""[{"id":"broken","symbol":""")
        }
    }
    @Test
    fun validJsonWithInvalidRecordThrowsInsteadOfDroppingRecord() {
        assertThrows(IllegalStateException::class.java) {
            SignalJournal.parseRecords(
                """[{"id":"good-1","time":1000,"symbol":"BTCUSDT","direction":"LONG","price":65000},{"id":"bad-2","time":2000,"symbol":"","direction":"LONG","price":0}]"""
            )
        }
    }

    @Test
    fun nonObjectJournalEntryThrowsInsteadOfBeingIgnored() {
        assertThrows(IllegalStateException::class.java) {
            SignalJournal.parseRecords(
                """[{"id":"good-1","time":1000,"symbol":"BTCUSDT","direction":"LONG","price":65000},null]"""
            )
        }
    }

}
