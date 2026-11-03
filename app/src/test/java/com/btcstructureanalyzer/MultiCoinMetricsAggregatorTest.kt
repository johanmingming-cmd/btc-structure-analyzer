package com.btcstructureanalyzer

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiCoinMetricsAggregatorTest {
    @Test fun poolsCountsAndWeightsMeanReturnByEvaluatedSignals() {
        val aggregator = MultiCoinMetricsAggregator()
        aggregator.add(HorizonEvaluation(12, 100, 20, 15, 5, 0, 80.0, 75.0, 1.0, 4.0))
        aggregator.add(HorizonEvaluation(12, 300, 60, 30, 30, 0, 80.0, 50.0, -0.5, 7.0))

        val result = aggregator.results().single()
        assertEquals(400, result.candidateCount)
        assertEquals(80, result.evaluated)
        assertEquals(45, result.directionalHits)
        assertEquals(35, result.misses)
        assertEquals(0, result.waitCount)
        assertEquals(20.0, result.coveragePercent!!, 0.0001)
        assertEquals(56.25, result.hitRatePercent!!, 0.0001)
        assertEquals(-0.125, result.meanSignedReturnPercent!!, 0.0001)
        assertEquals(7.0, result.maxAbsoluteReturnPercent!!, 0.0001)
    }

    @Test fun rejectsInconsistentPerCoinCounts() {
        val aggregator = MultiCoinMetricsAggregator()
        try {
            aggregator.add(HorizonEvaluation(12, 10, 8, 5, 2, 0, 2, 80.0, 62.5, 1.0, 2.0))
            throw AssertionError("Inconsistent outcome counts must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
