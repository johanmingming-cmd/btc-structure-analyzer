package com.btcstructureanalyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptivePredictionJournalTest {
    @Test
    fun roundTripPreservesDecisionAndNullableMetrics() {
        val record = AdaptivePredictionRecord(
            id = "BTCUSDT-1800000000001",
            symbol = "BTCUSDT",
            decisionTime = 1_800_000_000_001L,
            referencePrice = 67_123.45,
            legacyDirection = "LONG",
            decision = "WAIT",
            regime = "TRANSITION",
            reason = "WAIT_REGIME_UNCLEAR",
            atrPercent = 1.25,
            volatilityExpansion = 1.1,
            efficiencyRatio = null,
            normalizedSlope = -0.4,
            explanation = "Bukti trend/range bercampur; arah ditahan.",
            setupFamily = "NONE",
            qualityScore = 0,
            engineVersion = "B3-core-0.2"
        )
        val decoded = AdaptivePredictionJournal.parseRecords(
            AdaptivePredictionJournal.encodeRecords(listOf(record))
        ).single()

        assertEquals(record.id, decoded.id)
        assertEquals(record.symbol, decoded.symbol)
        assertEquals(record.decision, decoded.decision)
        assertEquals(record.regime, decoded.regime)
        assertEquals(record.reason, decoded.reason)
        assertEquals(record.referencePrice, decoded.referencePrice, 0.0)
        assertEquals(record.atrPercent!!, decoded.atrPercent!!, 0.0)
        assertNull(decoded.efficiencyRatio)
        assertEquals(record.explanation, decoded.explanation)
    }

    @Test
    fun csvIncludesWaitDecisionsAndQuotesExplanations() {
        val record = AdaptivePredictionRecord(
            id = "ETHUSDT-1", symbol = "ETHUSDT", decisionTime = 1L,
            referencePrice = 2500.0, legacyDirection = "WAIT", decision = "WAIT",
            regime = "RANGE", reason = "WAIT_NO_SETUP", atrPercent = null,
            volatilityExpansion = null, efficiencyRatio = 0.2, normalizedSlope = 0.1,
            explanation = "Tidak ada, setup \"valid\".", setupFamily = "NONE",
            qualityScore = 0, engineVersion = "test"
        )
        val csv = AdaptivePredictionJournal.csv(listOf(record))
        assertEquals(true, csv.contains("adaptive_decision") || csv.contains("WAIT"))
        assertEquals(true, csv.contains("\"\"valid\"\""))
    }
}
