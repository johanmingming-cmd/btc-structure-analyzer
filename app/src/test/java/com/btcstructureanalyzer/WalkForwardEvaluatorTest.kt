package com.btcstructureanalyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkForwardEvaluatorTest {
    private fun series(size: Int) = (0 until size).map { i ->
        val price = 100.0 + i
        Candle(i * 60_000L, price, price + 1, price - 1, price, 10.0, (i + 1) * 60_000L - 1)
    }

    @Test fun predictorNeverReceivesFutureCandles() {
        val seenSizes = mutableListOf<Int>()
        val result = WalkForwardEvaluator.evaluate(series(40), listOf(1, 3), 0.5) { history ->
            seenSizes.add(history.size)
            AdaptiveDecision.LONG
        }
        assertTrue(seenSizes.all { it < 40 })
        assertEquals(20, result.testStartInclusive)
        assertEquals(100.0, result.horizons.first().hitRatePercent!!, 0.0001)
    }

    @Test fun waitPredictionsAreExcludedFromDirectionalHitRate() {
        val result = WalkForwardEvaluator.evaluate(series(30), listOf(2), 0.5) {
            AdaptiveDecision.WAIT
        }
        assertEquals(0, result.horizons.single().evaluated)
        assertEquals(null, result.horizons.single().hitRatePercent)
    }
}
