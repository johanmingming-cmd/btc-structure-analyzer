package com.btcstructureanalyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveOutcomeEvaluatorTest {
    @Test
    fun longAndShortOutcomesUseDirectionalReturn() {
        val long = AdaptiveOutcomeEvaluator.classify("LONG", 100.0, 101.0)
        val short = AdaptiveOutcomeEvaluator.classify("SHORT", 100.0, 99.0)
        assertEquals("HIT", long.second)
        assertEquals(1.0, long.first, 0.000001)
        assertEquals("HIT", short.second)
        assertEquals(1.0, short.first, 0.000001)
    }

    @Test
    fun smallMoveIsNeutralForBothDirections() {
        assertEquals("NEUTRAL", AdaptiveOutcomeEvaluator.classify("LONG", 100.0, 100.02).second)
        assertEquals("NEUTRAL", AdaptiveOutcomeEvaluator.classify("SHORT", 100.0, 99.98).second)
    }

    @Test
    fun missIsDirectionalAndSummaryExcludesWait() {
        val prediction = AdaptivePredictionRecord(
            id = "BTCUSDT-1000", symbol = "BTCUSDT", decisionTime = 1_000L,
            referencePrice = 100.0, legacyDirection = "LONG", decision = "LONG",
            regime = "TREND", reason = "SIGNAL_CONFIRMED", atrPercent = 1.0,
            volatilityExpansion = 1.0, efficiencyRatio = 0.5, normalizedSlope = 1.5,
            explanation = "confirmed", setupFamily = "TREND", qualityScore = 80,
            engineVersion = "test"
        )
        val wait = prediction.copy(id = "ETHUSDT-1000", symbol = "ETHUSDT", decision = "WAIT")
        val outcome = AdaptiveOutcomeRecord(
            id = "BTCUSDT-1000-1h", predictionId = prediction.id, symbol = prediction.symbol,
            decision = "LONG", horizonHours = 1, targetTime = 3_601_000L,
            referencePrice = 100.0, evaluatedPrice = 99.0, evaluatedAt = 3_600_999L,
            signedReturnPercent = -1.0, result = "MISS"
        )
        val summary = AdaptiveOutcomeEvaluator.summarize(
            listOf(prediction, wait), listOf(outcome), 4_000_000L
        ).first()
        assertEquals(1, summary.eligible)
        assertEquals(1, summary.evaluated)
        assertEquals(1, summary.misses)
        assertEquals(0, summary.hits)
        assertEquals(0, summary.pending)
        assertNull(AdaptiveOutcomeEvaluator.summarize(listOf(wait), emptyList(), 4_000_000L).first().hitRatePercent)
    }
}
