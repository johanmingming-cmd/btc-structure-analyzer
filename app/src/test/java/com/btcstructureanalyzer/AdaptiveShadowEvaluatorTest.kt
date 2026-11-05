package com.btcstructureanalyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveShadowEvaluatorTest {
    private val decisionTime = 1_800_000_000_000L

    private fun candles(interval: Long, count: Int = 60): List<Candle> {
        val start = decisionTime - interval * count
        return (0 until count).map { i ->
            val open = 100.0 + i
            val close = open + 0.8
            Candle(
                start + i * interval, open, close + 0.2, open - 0.2,
                close, 1_000.0, start + i * interval + interval - 1
            )
        }
    }

    private fun input() = AdaptiveEngineInput(
        decisionTime,
        candles(3_600_000L),
        candles(900_000L),
        candles(300_000L)
    )

    @Test
    fun recordsAgreementWithoutChangingEitherDecision() {
        val record = AdaptiveShadowEvaluator.evaluate(
            "btcusdt", 159.25, AdaptiveDecision.LONG, input()
        )
        assertEquals("BTCUSDT", record.symbol)
        assertEquals(AdaptiveDecision.LONG, record.adaptive.decision)
        assertEquals(ShadowComparison.AGREE, record.comparison)
        assertEquals(true, record.agreesWithLegacy)
    }

    @Test
    fun recordsDisagreementAsDiagnosticOnly() {
        val record = AdaptiveShadowEvaluator.evaluate(
            "ETHUSDT", 3_000.0, AdaptiveDecision.SHORT, input()
        )
        assertEquals(ShadowComparison.DISAGREE, record.comparison)
        assertEquals(false, record.agreesWithLegacy)
        assertEquals(AdaptiveDecision.LONG, record.adaptive.decision)
    }

    @Test
    fun waitIsNotCountedAsAgreementOrDisagreement() {
        val stale = input().copy(
            candles1h = candles(3_600_000L).map {
                it.copy(
                    openTime = it.openTime - 10 * 3_600_000L,
                    closeTime = it.closeTime - 10 * 3_600_000L
                )
            }
        )
        val record = AdaptiveShadowEvaluator.evaluate(
            "SOLUSDT", 100.0, AdaptiveDecision.LONG, stale
        )
        assertEquals(AdaptiveDecision.WAIT, record.adaptive.decision)
        assertEquals(ShadowComparison.ADAPTIVE_WAIT, record.comparison)
        assertNull(record.agreesWithLegacy)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidReferencePrice() {
        AdaptiveShadowEvaluator.evaluate("BTCUSDT", Double.NaN, AdaptiveDecision.LONG, input())
    }
}
