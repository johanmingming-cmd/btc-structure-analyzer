package com.btcstructureanalyzer

import org.junit.Assert.assertEquals
import org.junit.Test

class RangeReversalDetectorTest {
    private fun base15(): MutableList<Candle> = (0 until 30).map { i ->
        Candle(i.toLong(), 100.0, 101.0, 99.0, 100.0, 1000.0, i.toLong() + 1)
    }.toMutableList()

    private fun base5(): MutableList<Candle> = (0 until 20).map { i ->
        Candle(i.toLong(), 100.0, 101.0, 99.0, 100.0, 1000.0, i.toLong() + 1)
    }.toMutableList()

    @Test fun confirmsSupportSweepOnlyAfterFiveMinuteBullishBreakout() {
        val m15 = base15()
        m15[m15.lastIndex] = Candle(30, 100.0, 101.0, 98.8, 100.3, 1400.0, 31)
        val m5 = base5()
        m5[m5.lastIndex] = Candle(20, 100.0, 102.0, 99.8, 101.8, 1300.0, 21)

        val result = RangeReversalDetector.detect(AdaptiveRegime.RANGE, m15, m5)
        assertEquals("Expected a confirmed support rejection, got ${result.waitReason}: ${result.explanation}",
            SetupStatus.CONFIRMED, result.status)
        assertEquals(RangeSetupFamily.RANGE_SUPPORT_REJECTION, result.family)
        assertEquals(AdaptiveDecision.LONG, result.direction)
        assert(result.explanation.contains("triggerBodyATR="))
        assert(result.explanation.contains("triggerBreakoutATR="))
    }

    @Test fun rejectsDeepBoundaryBreakInsteadOfCallingItRejection() {
        val m15 = base15()
        m15[m15.lastIndex] = Candle(30, 100.0, 101.0, 95.0, 100.3, 1400.0, 31)
        val result = RangeReversalDetector.detect(AdaptiveRegime.RANGE, m15, base5())
        assertEquals(SetupStatus.WAIT, result.status)
        assertEquals(RangeWaitReason.SWEEP_EXCEEDS_TOLERANCE, result.waitReason)
    }

    @Test fun reportsNoBoundarySweepSeparately() {
        val result = RangeReversalDetector.detect(AdaptiveRegime.RANGE, base15(), base5())
        assertEquals(SetupStatus.WAIT, result.status)
        assertEquals(RangeWaitReason.NO_BOUNDARY_SWEEP, result.waitReason)
        assert(result.explanation.startsWith("RANGE_WAIT_NO_BOUNDARY_SWEEP |"))
        assert(result.explanation.contains("supportSweepATR="))
        assert(result.explanation.contains("lowerWickBody="))
        assert(result.explanation.contains("volumeRatio="))
    }

    @Test fun reportsMissingFiveMinuteTriggerSeparately() {
        val m15 = base15()
        m15[m15.lastIndex] = Candle(30, 100.0, 101.0, 98.8, 100.3, 1400.0, 31)
        val result = RangeReversalDetector.detect(AdaptiveRegime.RANGE, m15, base5())
        assertEquals(SetupStatus.WAIT, result.status)
        assertEquals(RangeWaitReason.NO_5M_CONFIRMATION, result.waitReason)
    }

    @Test fun rejectsInvertedHighLowRange() {
        val m15 = base15()
        m15[10] = m15[10].copy(high = 99.0, low = 100.0)
        val result = RangeReversalDetector.detect(AdaptiveRegime.RANGE, m15, base5())
        assertEquals(SetupStatus.WAIT, result.status)
        assertEquals(RangeWaitReason.INSUFFICIENT_DATA, result.waitReason)
    }

    @Test fun rejectsOutOfOrderCandlesAtDetectorBoundary() {
        val m15 = base15()
        m15[m15.lastIndex] = m15[m15.lastIndex].copy(openTime = 10L, closeTime = 11L)
        val result = RangeReversalDetector.detect(AdaptiveRegime.RANGE, m15, base5())
        assertEquals(SetupStatus.WAIT, result.status)
        assertEquals(RangeWaitReason.INSUFFICIENT_DATA, result.waitReason)
    }

    @Test fun rejectsDuplicateCandleTimestampsAtDetectorBoundary() {
        val m5 = base5()
        m5[m5.lastIndex] = m5[m5.lastIndex].copy(openTime = 18L, closeTime = 19L)
        val result = RangeReversalDetector.detect(AdaptiveRegime.RANGE, base15(), m5)
        assertEquals(SetupStatus.WAIT, result.status)
        assertEquals(RangeWaitReason.INSUFFICIENT_DATA, result.waitReason)
    }

    @Test fun rejectsNonFiniteTolerance() {
        val result = RangeReversalDetector.detect(
            AdaptiveRegime.RANGE, base15(), base5(),
            RangeDetectionConfig(boundaryAtrTolerance = Double.NaN)
        )
        assertEquals(SetupStatus.WAIT, result.status)
        assertEquals(RangeWaitReason.INSUFFICIENT_DATA, result.waitReason)
    }
}
