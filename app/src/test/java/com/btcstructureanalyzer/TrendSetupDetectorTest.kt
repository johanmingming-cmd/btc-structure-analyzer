package com.btcstructureanalyzer

import org.junit.Assert.assertEquals
import org.junit.Test

class TrendSetupDetectorTest {
    private fun series(size: Int, close: Double = 100.2, volume: Double = 1000.0): List<Candle> =
        (0 until size).map { i ->
            Candle(i.toLong(), 100.0, 101.0, 99.0, close, volume, i.toLong() + 1)
        }

    @Test fun confirmsLongContinuationOnlyAfterClosedFiveMinuteBreakout() {
        val fifteen = series(35).toMutableList()
        fifteen[fifteen.lastIndex] = Candle(35, 101.0, 103.4, 100.8, 103.0, 1200.0, 36)
        val five = series(20).toMutableList()
        five[five.lastIndex] = Candle(20, 100.5, 102.4, 100.3, 102.0, 1200.0, 21)

        val result = TrendSetupDetector.detect(
            AdaptiveRegime.TREND, AdaptiveDecision.LONG, AdaptiveDecision.LONG, fifteen, five
        )
        assertEquals(SetupStatus.CONFIRMED, result.status)
        assertEquals(SetupFamily.TREND_CONTINUATION, result.family)
        assertEquals(AdaptiveDecision.LONG, result.direction)
    }

    @Test fun waitsWhenLowerTimeframeTriggerIsMissing() {
        val fifteen = series(35).toMutableList()
        fifteen[fifteen.lastIndex] = Candle(35, 101.0, 103.4, 100.8, 103.0, 1200.0, 36)
        val five = series(20)

        val result = TrendSetupDetector.detect(
            AdaptiveRegime.TREND, AdaptiveDecision.LONG, AdaptiveDecision.LONG, fifteen, five
        )
        assertEquals(SetupStatus.WAIT, result.status)
        assertEquals(SetupWaitReason.NO_5M_TRIGGER, result.waitReason)
    }

    @Test fun waitsWhenHigherTimeframesDisagree() {
        val result = TrendSetupDetector.detect(
            AdaptiveRegime.TREND, AdaptiveDecision.LONG, AdaptiveDecision.SHORT, series(35), series(20)
        )
        assertEquals(SetupStatus.WAIT, result.status)
        assertEquals(SetupWaitReason.TIMEFRAME_DISAGREEMENT, result.waitReason)
    }

    @Test fun waitsOutsideTrendRegime() {
        val result = TrendSetupDetector.detect(
            AdaptiveRegime.RANGE, AdaptiveDecision.LONG, AdaptiveDecision.LONG, series(35), series(20)
        )
        assertEquals(SetupStatus.WAIT, result.status)
        assertEquals(SetupWaitReason.REGIME_NOT_TREND, result.waitReason)
    }
}
