package com.btcstructureanalyzer

/** Pools per-coin walk-forward rows without averaging coin hit rates. */
class MultiCoinMetricsAggregator {
    private data class Totals(
        var candidates: Int = 0,
        var evaluated: Int = 0,
        var hits: Int = 0,
        var misses: Int = 0,
        var neutral: Int = 0,
        var waits: Int = 0,
        var signedReturnSum: Double = 0.0,
        var maxAbsoluteReturn: Double = 0.0
    )

    private val totals = mutableMapOf<Int, Totals>()

    fun add(row: HorizonEvaluation) {
        require(row.horizonBars > 0)
        require(row.candidateCount >= 0 && row.evaluated >= 0 && row.directionalHits >= 0)
        require(row.misses >= 0 && row.neutral >= 0 && row.waitCount >= 0)
        require(row.evaluated == row.directionalHits + row.misses + row.neutral)
        require(row.candidateCount == row.evaluated + row.waitCount)
        val mean = row.meanSignedReturnPercent
        val maxAbs = row.maxAbsoluteReturnPercent
        require(mean == null || mean.isFinite())
        require(maxAbs == null || (maxAbs.isFinite() && maxAbs >= 0.0))
        val total = totals.getOrPut(row.horizonBars) { Totals() }
        total.candidates += row.candidateCount
        total.evaluated += row.evaluated
        total.hits += row.directionalHits
        total.misses += row.misses
        total.neutral += row.neutral
        total.waits += row.waitCount
        total.signedReturnSum += (mean ?: 0.0) * row.evaluated
        total.maxAbsoluteReturn = maxOf(total.maxAbsoluteReturn, maxAbs ?: 0.0)
    }

    fun horizons(): Set<Int> = totals.keys.toSet()

    fun results(): List<HorizonEvaluation> = totals.toSortedMap().map { (horizon, total) ->
        HorizonEvaluation(
            horizonBars = horizon,
            candidateCount = total.candidates,
            evaluated = total.evaluated,
            directionalHits = total.hits,
            misses = total.misses,
            neutral = total.neutral,
            waitCount = total.waits,
            coveragePercent = if (total.candidates > 0) total.evaluated * 100.0 / total.candidates else null,
            hitRatePercent = if (total.evaluated > 0) total.hits * 100.0 / total.evaluated else null,
            meanSignedReturnPercent = if (total.evaluated > 0) total.signedReturnSum / total.evaluated else null,
            maxAbsoluteReturnPercent = if (total.evaluated > 0) total.maxAbsoluteReturn else null
        )
    }
}
