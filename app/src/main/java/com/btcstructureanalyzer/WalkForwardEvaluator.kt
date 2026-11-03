package com.btcstructureanalyzer

import kotlin.math.abs

data class HorizonEvaluation(
    val horizonBars: Int,
    val evaluated: Int,
    val directionalHits: Int,
    val misses: Int,
    val neutral: Int,
    val hitRatePercent: Double?,
    val meanSignedReturnPercent: Double?,
    val maxAbsoluteReturnPercent: Double?
)

data class WalkForwardEvaluation(
    val trainEndExclusive: Int,
    val testStartInclusive: Int,
    val testEndExclusive: Int,
    val horizons: List<HorizonEvaluation>,
    val notes: List<String>
)

/**
 * Chronological, no-look-ahead evaluator.
 * predictor receives only candles up to and including the decision candle.
 * Outcome is measured at the close N bars later; no TP/SL assumptions are used.
 */
object WalkForwardEvaluator {
    fun evaluate(
        candles: List<Candle>,
        horizons: List<Int>,
        trainFraction: Double = 0.60,
        predictor: (historyThroughDecision: List<Candle>) -> AdaptiveDecision
    ): WalkForwardEvaluation {
        require(candles.size >= 20)
        require(horizons.isNotEmpty() && horizons.all { it > 0 } && horizons.distinct().size == horizons.size)
        require(trainFraction in 0.1..0.9)
        require(candles.zipWithNext().all { (a, b) -> b.openTime > a.openTime })
        require(candles.all { c ->
            c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite() &&
                c.close > 0.0 && c.open > 0.0 && c.high >= maxOf(c.open, c.close) &&
                c.low <= minOf(c.open, c.close) && c.low > 0.0
        })

        val split = (candles.size * trainFraction).toInt().coerceIn(1, candles.size - 1)
        val largestHorizon = horizons.max()
        val rows = horizons.map { horizon ->
            var evaluated = 0
            var hits = 0
            var misses = 0
            var neutral = 0
            var signedReturns = mutableListOf<Double>()
            var absReturns = mutableListOf<Double>()
            for (index in split until candles.size - horizon) {
                val decision = predictor(candles.subList(0, index + 1).toList())
                if (decision == AdaptiveDecision.WAIT) continue
                val start = candles[index].close
                val end = candles[index + horizon].close
                val rawReturn = (end / start - 1.0) * 100.0
                val signed = if (decision == AdaptiveDecision.LONG) rawReturn else -rawReturn
                evaluated++
                signedReturns.add(signed)
                absReturns.add(abs(rawReturn))
                when {
                    signed > 0.0 -> hits++
                    signed < 0.0 -> misses++
                    else -> neutral++
                }
            }
            HorizonEvaluation(
                horizon, evaluated, hits, misses, neutral,
                if (evaluated > 0) hits.toDouble() / evaluated * 100.0 else null,
                signedReturns.takeIf { it.isNotEmpty() }?.average(),
                absReturns.maxOrNull()
            )
        }
        return WalkForwardEvaluation(
            split, split, candles.size - largestHorizon, rows,
            listOf(
                "Prediktor hanya menerima data sampai candle keputusan; candle masa depan hanya dipakai untuk scoring.",
                "Split kronologis; tidak ada pengacakan data.",
                "Return diukur pada close horizon; tidak menggunakan TP/SL, fee, slippage, atau simulasi eksekusi.",
                "Hit rate bukan probabilitas kemenangan dan tidak membuktikan performa live."
            )
        )
    }
}
