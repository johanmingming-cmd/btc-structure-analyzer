package com.btcstructureanalyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePredictionEngineTest {
    private val decisionTime = 1_800_000_000_000L

    private fun candles(interval: Long, count: Int = 60, trend: Boolean = true): List<Candle> {
        val start = decisionTime - interval * count
        return (0 until count).map { i ->
            val open = if (trend) 100.0 + i else 100.0 + if (i % 2 == 0) 0.2 else 0.0
            val close = if (trend) open + 0.8 else 100.0 + if (i % 2 == 0) 0.0 else 0.2
            Candle(start + i * interval, open, maxOf(open, close) + 0.2,
                minOf(open, close) - 0.2, close, 1000.0, start + i * interval + interval - 1)
        }
    }

    private fun input(
        h1: List<Candle> = candles(3_600_000L),
        m15: List<Candle> = candles(900_000L),
        m5: List<Candle> = candles(300_000L)
    ) = AdaptiveEngineInput(decisionTime, h1, m15, m5)

    @Test fun classifiesDirectionalPricePathAsTrendButDoesNotEmitSignalYet() {
        val result = AdaptivePredictionEngine.evaluate(input())
        assertEquals(AdaptiveRegime.TREND, result.regime)
        assertEquals(AdaptiveDecision.WAIT, result.decision)
        assertEquals(AdaptiveReason.WAIT_NO_SETUP, result.reason)
        assertNotNull(result.atrPercent)
    }

    @Test fun excludesStillOpenLatestCandle() {
        val closed = candles(3_600_000L)
        val open = Candle(decisionTime, 160.0, 161.0, 159.0, 160.5, 1000.0, decisionTime + 3_599_999L)
        val result = AdaptivePredictionEngine.evaluate(input(h1 = closed + open))
        assertEquals(closed.last().openTime, result.latestClosed1hTime)
    }

    @Test fun rejectsMissingCandleInterval() {
        val source = candles(3_600_000L).toMutableList()
        source.removeAt(20)
        val result = AdaptivePredictionEngine.evaluate(input(h1 = source))
        assertEquals(AdaptiveDecision.WAIT, result.decision)
        assertEquals(AdaptiveReason.WAIT_DATA_GAP, result.reason)
    }

    @Test fun rejectsStaleClosedCandles() {
        val source = candles(3_600_000L).map {
            it.copy(openTime = it.openTime - 10 * 3_600_000L, closeTime = it.closeTime - 10 * 3_600_000L)
        }
        val result = AdaptivePredictionEngine.evaluate(input(h1 = source))
        assertEquals(AdaptiveReason.WAIT_DATA_STALE, result.reason)
    }

    @Test fun shockStateTakesPrecedenceOverTrend() {
        val base = candles(3_600_000L).toMutableList()
        for (i in base.lastIndex - 13..base.lastIndex) {
            val c = base[i]
            base[i] = c.copy(high = c.high + 15.0, low = c.low - 15.0)
        }
        val result = AdaptivePredictionEngine.evaluate(input(h1 = base))
        assertEquals(AdaptiveRegime.VOLATILITY_SHOCK, result.regime)
        assertEquals(AdaptiveReason.WAIT_VOLATILITY_SHOCK, result.reason)
    }
}
