package com.btcstructureanalyzer

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiCoinMetricsAggregatorTest {
    @Test fun poolsCountsAndWeightsMeanReturnByEvaluatedSignals() {
        val aggregator = MultiCoinMetricsAggregator()
        aggregator.add(HorizonEvaluation(12, 100, 20, 15, 5, 0, 80, 20.0, 75.0, 1.0, 4.0))
        aggregator.add(HorizonEvaluation(12, 300, 60, 30, 30, 0, 240, 20.0, 50.0, -0.5, 7.0))

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

    @Test fun leavesMaximumReturnUndefinedWhenEveryPredictionWaits() {
        val aggregator = MultiCoinMetricsAggregator()
        aggregator.add(HorizonEvaluation(12, 10, 0, 0, 0, 0, 10, 0.0, null, null, null))

        val result = aggregator.results().single()
        assertEquals(10, result.candidateCount)
        assertEquals(0, result.evaluated)
        assertEquals(10, result.waitCount)
        assertEquals(null, result.maxAbsoluteReturnPercent)
        assertEquals(null, result.meanSignedReturnPercent)
    }

    @Test fun rejectsMissingReturnMetricsWhenOutcomesWereEvaluated() {
        val aggregator = MultiCoinMetricsAggregator()
        try {
            aggregator.add(HorizonEvaluation(12, 1, 1, 1, 0, 0, 0, 100.0, 100.0, null, null))
            throw AssertionError("Evaluated outcomes must include return metrics")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }


    @Test fun rejectsMeanReturnLargerThanMaximumAbsoluteReturn() {
        val aggregator = MultiCoinMetricsAggregator()
        try {
            aggregator.add(HorizonEvaluation(12, 1, 1, 1, 0, 0, 0, 100.0, 100.0, 2.0, 1.0))
            throw AssertionError("Mean return cannot exceed maximum absolute return")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test fun rejectsNonFinitePooledReturnSum() {
        val aggregator = MultiCoinMetricsAggregator()
        try {
            aggregator.add(HorizonEvaluation(12, 2, 2, 2, 0, 0, 0, 100.0, 100.0, Double.MAX_VALUE, Double.MAX_VALUE))
            throw AssertionError("Pooled return overflow must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test fun rejectsCountOverflowInsteadOfWrapping() {
        val aggregator = MultiCoinMetricsAggregator()
        aggregator.add(HorizonEvaluation(12, Int.MAX_VALUE, 0, 0, 0, 0, Int.MAX_VALUE, 0.0, null, null, null))
        try {
            aggregator.add(HorizonEvaluation(12, 1, 0, 0, 0, 0, 1, 0.0, null, null, null))
            throw AssertionError("Pooled count overflow must be rejected")
        } catch (_: ArithmeticException) {
            // Expected.
        }
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
