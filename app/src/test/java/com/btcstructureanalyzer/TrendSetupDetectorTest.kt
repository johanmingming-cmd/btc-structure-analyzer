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

    @Test fun rejectsNonFiniteSetupThresholds() {
        val invalidConfigs = listOf(
            SetupDetectionConfig(pullbackMaxDistanceAtr = Double.NaN),
            SetupDetectionConfig(minimumBodyAtr = Double.POSITIVE_INFINITY),
            SetupDetectionConfig(minimumVolumeRatio = Double.NaN)
        )
        invalidConfigs.forEach { config ->
            val result = TrendSetupDetector.detect(
                AdaptiveRegime.TREND, AdaptiveDecision.LONG, AdaptiveDecision.LONG,
                series(35), series(20), config
            )
            assertEquals(SetupStatus.WAIT, result.status)
            assertEquals(SetupWaitReason.INSUFFICIENT_DATA, result.waitReason)
        }
    }

    @Test fun rejectsInvertedHighLowRange() {
        val fifteen = series(35).toMutableList()
        fifteen[10] = fifteen[10].copy(high = 99.0, low = 100.0)
        val result = TrendSetupDetector.detect(
            AdaptiveRegime.TREND, AdaptiveDecision.LONG, AdaptiveDecision.LONG, fifteen, series(20)
        )
        assertEquals(SetupStatus.WAIT, result.status)
        assertEquals(SetupWaitReason.INSUFFICIENT_DATA, result.waitReason)
    }

    @Test fun rejectsOutOfOrderCandlesAtDetectorBoundary() {
        val fifteen = series(35).toMutableList()
        fifteen[fifteen.lastIndex] = fifteen[fifteen.lastIndex].copy(openTime = 10L, closeTime = 11L)
        val result = TrendSetupDetector.detect(
            AdaptiveRegime.TREND, AdaptiveDecision.LONG, AdaptiveDecision.LONG, fifteen, series(20)
        )
        assertEquals(SetupStatus.WAIT, result.status)
        assertEquals(SetupWaitReason.INSUFFICIENT_DATA, result.waitReason)
    }

    @Test fun rejectsDuplicateCandleTimestampsAtDetectorBoundary() {
        val five = series(20).toMutableList()
        five[five.lastIndex] = five[five.lastIndex].copy(openTime = 18L, closeTime = 19L)
        val result = TrendSetupDetector.detect(
            AdaptiveRegime.TREND, AdaptiveDecision.LONG, AdaptiveDecision.LONG, series(35), five
        )
        assertEquals(SetupStatus.WAIT, result.status)
        assertEquals(SetupWaitReason.INSUFFICIENT_DATA, result.waitReason)
    }

    @Test fun experimentalPullbackCandidateRejectsNearEmaWithoutActualRetracement() {
        val fifteen = series(35).toMutableList()
        fifteen[31] = Candle(31, 100.0, 101.0, 99.0, 100.1, 1000.0, 32)
        fifteen[32] = Candle(32, 100.1, 101.0, 99.0, 100.2, 1000.0, 33)
        fifteen[33] = Candle(33, 100.2, 101.0, 99.0, 100.3, 1000.0, 34)
        fifteen[34] = Candle(35, 100.2, 101.0, 99.8, 100.8, 1200.0, 36)
        val five = series(20).toMutableList()
        five[five.lastIndex] = Candle(20, 100.5, 102.4, 100.3, 102.0, 1200.0, 21)

        val baseline = TrendSetupDetector.detect(
            AdaptiveRegime.TREND, AdaptiveDecision.LONG, AdaptiveDecision.LONG, fifteen, five
        )
        val candidate = TrendSetupDetector.detect(
            AdaptiveRegime.TREND, AdaptiveDecision.LONG, AdaptiveDecision.LONG, fifteen, five,
            SetupDetectionConfig(minimumPullbackDepthAtr = 0.15, pullbackLookbackBars = 3)
        )

        assertEquals(SetupFamily.TREND_PULLBACK, baseline.family)
        assertEquals(SetupStatus.CONFIRMED, baseline.status)
        assertEquals(SetupStatus.WAIT, candidate.status)
        assertEquals("PULLBACK_RETRACEMENT_TOO_SHALLOW", candidate.diagnosticDetail)
    }

    @Test fun waitsOutsideTrendRegime() {
        val result = TrendSetupDetector.detect(
            AdaptiveRegime.RANGE, AdaptiveDecision.LONG, AdaptiveDecision.LONG, series(35), series(20)
        )
        assertEquals(SetupStatus.WAIT, result.status)
        assertEquals(SetupWaitReason.REGIME_NOT_TREND, result.waitReason)
    }
    @Test fun reportsSmallFifteenMinuteBodyAsItsOwnWaitCause() {
        val result = TrendSetupDetector.detect(
            AdaptiveRegime.TREND, AdaptiveDecision.LONG, AdaptiveDecision.LONG,
            series(35), series(20)
        )
        assertEquals(SetupWaitReason.NO_15M_PATTERN, result.waitReason)
        assertEquals("FIFTEEN_MINUTE_BODY_TOO_SMALL", result.diagnosticDetail)
    }

    @Test fun distinguishesMissingFiveMinuteBreakoutTrigger() {
        val fifteen = series(35).toMutableList()
        fifteen[fifteen.lastIndex] = Candle(35, 101.0, 103.4, 100.8, 103.0, 1200.0, 36)
        val result = TrendSetupDetector.detect(
            AdaptiveRegime.TREND, AdaptiveDecision.LONG, AdaptiveDecision.LONG,
            fifteen, series(20)
        )
        assertEquals(SetupWaitReason.NO_5M_TRIGGER, result.waitReason)
        assertEquals("FIVE_MINUTE_BREAKOUT_TRIGGER_NOT_MET", result.diagnosticDetail)
    }

}
