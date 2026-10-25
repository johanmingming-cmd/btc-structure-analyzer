package com.btcstructureanalyzer

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalJournalHistoricalEvaluationTest {
    private fun signal(direction: String = "LONG") = SignalRecord(
        id = "test",
        time = 0L,
        symbol = "TESTUSDT",
        direction = direction,
        price = 100.0,
        entryLow = 99.0,
        entryHigh = 101.0,
        stopLoss = if (direction == "LONG") 95.0 else 105.0,
        takeProfit1 = if (direction == "LONG") 105.0 else 95.0,
        takeProfit2 = if (direction == "LONG") 110.0 else 90.0,
        score = 70,
        supports = emptyList(),
        resistances = emptyList()
    )

    private fun bar(minute: Int, high: Double = 102.0, low: Double = 98.0) =
        HistoricalBar(
            openTime = minute * 60_000L,
            high = high,
            low = low,
            closeTime = minute * 60_000L + 59_999L
        )

    private fun completeBars() = (1..59).map { minute ->
        when (minute) {
            1 -> bar(minute, high = 102.0, low = 99.5) // entry in the first full minute
            else -> bar(minute, high = 103.0, low = 98.0)
        }
    }

    @Test
    fun missingEntryWindowCandleIsInsufficientDataNotMissedEntry() {
        val bars = completeBars().filterNot { it.openTime == 5 * 60_000L }
        assertEquals("DATA_TIDAK_CUKUP", SignalJournal.evaluateTradeOutcome(signal(), bars).status)
    }

    @Test
    fun tpAndSlInSamePostEntryCandleIsAmbiguous() {
        val bars = completeBars().map { b ->
            if (b.openTime == 2 * 60_000L) b.copy(high = 106.0, low = 94.0) else b
        }
        assertEquals("AMBIGU", SignalJournal.evaluateTradeOutcome(signal(), bars).status)
    }

    @Test
    fun targetReachedAfterEntryIsRecorded() {
        val bars = completeBars().map { b ->
            if (b.openTime == 2 * 60_000L) b.copy(high = 106.0, low = 98.0) else b
        }
        assertEquals("TP1_TERCAPAI", SignalJournal.evaluateTradeOutcome(signal(), bars).status)
    }

    @Test
    fun missingPostEntryCandleIsInsufficientDataIfNoTargetWasReached() {
        val bars = completeBars()
            .filterNot { it.openTime == 30 * 60_000L }
            .map { it.copy(high = 103.0, low = 98.0) }
        assertEquals("DATA_TIDAK_CUKUP", SignalJournal.evaluateTradeOutcome(signal(), bars).status)
    }
    @Test
    fun malformedPartialMinuteCandleIsInsufficientData() {
        val bars = completeBars().map { b ->
            if (b.openTime == 5 * 60_000L) b.copy(closeTime = b.openTime + 30_000L) else b
        }
        assertEquals("DATA_TIDAK_CUKUP", SignalJournal.evaluateTradeOutcome(signal(), bars).status)
    }

}
