package com.btcstructureanalyzer

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BinanceHistoricalCloseTest {
    private fun candle(openTime: Long, closeTime: Long, close: Double) = JSONArray()
        .put(openTime).put("1.0").put("2.0").put("0.5").put(close.toString()).put("100.0").put(closeTime)

    @Test fun selectsLatestCompletedCandleAtOrBeforeTarget() {
        val rows = JSONArray()
            .put(candle(0L, 59_999L, 1.1))
            .put(candle(60_000L, 119_999L, 1.2))
        assertEquals(1.2, selectHistoricalSpotClose(rows, 120_000L)!!.first, 0.0)
        assertEquals(119_999L, selectHistoricalSpotClose(rows, 120_000L)!!.second)
    }

    @Test fun neverUsesCandleThatClosesAfterTarget() {
        val rows = JSONArray().put(candle(60_000L, 119_999L, 1.2))
        assertNull(selectHistoricalSpotClose(rows, 119_000L))
    }

    @Test fun rejectsStaleAndIncompleteCandles() {
        val rows = JSONArray()
            .put(candle(0L, 59_999L, 1.1))
            .put(candle(60_000L, 90_000L, 1.2))
        assertNull(selectHistoricalSpotClose(rows, 120_001L))
    }

    @Test fun noEligibleCandleReturnsNullSoCallerCanTryNextHost() {
        assertNull(selectHistoricalSpotClose(JSONArray(), 120_000L))
    }
}
