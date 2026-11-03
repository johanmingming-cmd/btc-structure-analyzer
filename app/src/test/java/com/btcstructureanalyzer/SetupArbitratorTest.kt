package com.btcstructureanalyzer

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupArbitratorTest {
    @Test fun opposingConfirmedSetupsBecomeWait() {
        val trend = SetupDetectionResult(SetupFamily.TREND_CONTINUATION, SetupStatus.CONFIRMED,
            AdaptiveDecision.LONG, null, 90, "trend")
        val range = RangeDetectionResult(RangeSetupFamily.RANGE_RESISTANCE_REJECTION, SetupStatus.CONFIRMED,
            AdaptiveDecision.SHORT, null, 95, "range")
        val result = SetupArbitrator.select(trend, range)
        assertEquals(AdaptiveDecision.WAIT, result.decision)
        assertEquals(ArbitrationReason.CONFLICTING_DIRECTIONS, result.reason)
    }

    @Test fun singleConfirmedSetupIsSelected() {
        val trend = SetupDetectionResult(SetupFamily.TREND_PULLBACK, SetupStatus.CONFIRMED,
            AdaptiveDecision.SHORT, null, 82, "trend")
        val range = RangeDetectionResult(RangeSetupFamily.NONE, SetupStatus.WAIT,
            AdaptiveDecision.WAIT, RangeWaitReason.REGIME_NOT_RANGE, 0, "wait")
        val result = SetupArbitrator.select(trend, range)
        assertEquals(AdaptiveDecision.SHORT, result.decision)
        assertEquals(SelectedSetupFamily.TREND_PULLBACK, result.family)
    }
}
