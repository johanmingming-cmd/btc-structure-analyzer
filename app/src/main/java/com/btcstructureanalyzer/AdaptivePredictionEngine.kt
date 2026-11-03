package com.btcstructureanalyzer

import kotlin.math.abs
import kotlin.math.max

enum class AdaptiveRegime { TREND, RANGE, TRANSITION, VOLATILITY_SHOCK, UNKNOWN }
enum class AdaptiveDecision { LONG, SHORT, WAIT }
enum class AdaptiveReason {
    WAIT_DATA_STALE, WAIT_DATA_GAP, WAIT_DATA_INVALID, WAIT_INSUFFICIENT_HISTORY,
    WAIT_REGIME_UNCLEAR, WAIT_VOLATILITY_SHOCK, WAIT_NO_SETUP, SIGNAL_CONFIRMED
}

data class AdaptiveEngineConfig(
    val minBars: Int = 50,
    val atrPeriod: Int = 14,
    val atrBaselinePeriod: Int = 30,
    val maxStalenessIntervals: Int = 2,
    val shockExpansionRatio: Double = 1.8,
    val trendEfficiencyThreshold: Double = 0.38,
    val trendSlopeAtrThreshold: Double = 1.25,
    val minimumBiasEfficiency: Double = 0.25,
    val minimumBiasSlopeAtr: Double = 0.25,
    val trendSetup: SetupDetectionConfig = SetupDetectionConfig(),
    val rangeSetup: RangeDetectionConfig = RangeDetectionConfig()
)

data class AdaptiveEngineInput(
    val decisionTime: Long,
    val candles1h: List<Candle>,
    val candles15m: List<Candle>,
    val candles5m: List<Candle>
)

data class AdaptiveEngineResult(
    val decision: AdaptiveDecision,
    val regime: AdaptiveRegime,
    val reason: AdaptiveReason,
    val decisionTime: Long,
    val latestClosed1hTime: Long?,
    val atrPercent: Double?,
    val volatilityExpansion: Double?,
    val efficiencyRatio: Double?,
    val normalizedSlope: Double?,
    val explanation: String,
    val setupFamily: SelectedSetupFamily = SelectedSetupFamily.NONE,
    val qualityScore: Int = 0,
    val engineVersion: String = "B3-core-0.2"
)

object AdaptivePredictionEngine {
    private const val HOUR_MS = 3_600_000L
    private const val FIFTEEN_MIN_MS = 900_000L
    private const val FIVE_MIN_MS = 300_000L

    fun evaluate(input: AdaptiveEngineInput, config: AdaptiveEngineConfig = AdaptiveEngineConfig()): AdaptiveEngineResult {
        require(config.minBars >= config.atrPeriod + 2)
        require(config.atrPeriod >= 2 && config.atrBaselinePeriod >= 2)
        require(config.maxStalenessIntervals >= 1)
        require(config.shockExpansionRatio.isFinite() && config.shockExpansionRatio > 1.0)
        require(config.trendEfficiencyThreshold.isFinite() && config.trendEfficiencyThreshold in 0.0..1.0)
        require(config.trendSlopeAtrThreshold.isFinite() && config.trendSlopeAtrThreshold >= 0.0)
        require(config.minimumBiasEfficiency.isFinite() && config.minimumBiasEfficiency in 0.0..1.0)
        require(config.minimumBiasSlopeAtr.isFinite() && config.minimumBiasSlopeAtr >= 0.0)
        require(input.decisionTime > 0L)

        val oneHour = validateAndClose(input.candles1h, HOUR_MS, input.decisionTime, config)
        val fifteen = validateAndClose(input.candles15m, FIFTEEN_MIN_MS, input.decisionTime, config)
        val five = validateAndClose(input.candles5m, FIVE_MIN_MS, input.decisionTime, config)
        val invalid = listOf(oneHour, fifteen, five).firstOrNull { it.reason != null }
        if (invalid != null) {
            val reason = invalid.reason!!
            return waiting(input.decisionTime, reason, invalid.latestTime,
                "Input candle ditolak: ${reason.name}. Tidak ada arah yang diterbitkan.")
        }

        val h1 = oneHour.candles
        val m15 = fifteen.candles
        val m5 = five.candles
        if (h1.size < config.minBars || m15.size < config.minBars || m5.size < config.minBars) {
            return waiting(input.decisionTime, AdaptiveReason.WAIT_INSUFFICIENT_HISTORY,
                h1.lastOrNull()?.openTime, "Riwayat candle tertutup belum memenuhi batas minimum.")
        }

        val metrics = regimeMetrics(h1, config)
            ?: return waiting(input.decisionTime, AdaptiveReason.WAIT_INSUFFICIENT_HISTORY,
                h1.lastOrNull()?.openTime, "Data volatilitas belum cukup untuk klasifikasi regime.")

        val regime = when {
            metrics.expansion >= config.shockExpansionRatio -> AdaptiveRegime.VOLATILITY_SHOCK
            metrics.efficiency >= config.trendEfficiencyThreshold &&
                abs(metrics.slopeAtr) >= config.trendSlopeAtrThreshold -> AdaptiveRegime.TREND
            metrics.efficiency <= 0.22 -> AdaptiveRegime.RANGE
            else -> AdaptiveRegime.TRANSITION
        }
        if (regime == AdaptiveRegime.VOLATILITY_SHOCK) {
            return result(input, h1, metrics, regime, AdaptiveDecision.WAIT,
                AdaptiveReason.WAIT_VOLATILITY_SHOCK, "Ekspansi volatilitas melewati ambang; sistem menahan sinyal.")
        }
        if (regime == AdaptiveRegime.TRANSITION || regime == AdaptiveRegime.UNKNOWN) {
            return result(input, h1, metrics, regime, AdaptiveDecision.WAIT,
                AdaptiveReason.WAIT_REGIME_UNCLEAR, "Bukti trend/range bercampur; arah ditahan.")
        }

        val trend = if (regime == AdaptiveRegime.TREND) {
            val bias1h = directionalBias(h1, config)
            val bias15m = directionalBias(m15, config)
            TrendSetupDetector.detect(regime, bias1h, bias15m, m15, m5, config.trendSetup)
        } else {
            SetupDetectionResult(SetupFamily.NONE, SetupStatus.WAIT, AdaptiveDecision.WAIT,
                SetupWaitReason.REGIME_NOT_TREND, 0, "Bukan regime trend.")
        }
        val range = if (regime == AdaptiveRegime.RANGE) {
            RangeReversalDetector.detect(regime, m15, m5, config.rangeSetup)
        } else {
            RangeDetectionResult(RangeSetupFamily.NONE, SetupStatus.WAIT, AdaptiveDecision.WAIT,
                RangeWaitReason.REGIME_NOT_RANGE, 0, "Bukan regime range.")
        }
        val selected = SetupArbitrator.select(trend, range)
        val family = selected.family
        val reason = if (selected.decision == AdaptiveDecision.WAIT)
            AdaptiveReason.WAIT_NO_SETUP else AdaptiveReason.SIGNAL_CONFIRMED
        return AdaptiveEngineResult(
            selected.decision, regime, reason, input.decisionTime, h1.last().openTime,
            metrics.atrPercent, metrics.expansion, metrics.efficiency, metrics.slopeAtr,
            selected.explanation, family, selected.qualityScore
        )
    }

    private data class ValidatedSeries(val candles: List<Candle>, val reason: AdaptiveReason?, val latestTime: Long?)

    private fun validateAndClose(source: List<Candle>, interval: Long, decisionTime: Long, config: AdaptiveEngineConfig): ValidatedSeries {
        if (source.isEmpty()) return ValidatedSeries(emptyList(), AdaptiveReason.WAIT_INSUFFICIENT_HISTORY, null)
        val closed = source.filter { it.closeTime < decisionTime }
        if (closed.any { c ->
                c.openTime < 0L || c.closeTime != c.openTime + interval - 1L ||
                    !c.open.isFinite() || !c.high.isFinite() || !c.low.isFinite() || !c.close.isFinite() ||
                    !c.volume.isFinite() || c.open <= 0.0 || c.low <= 0.0 || c.high <= 0.0 ||
                    c.close <= 0.0 || c.volume < 0.0 || c.high < max(c.open, c.close) ||
                    c.low > minOf(c.open, c.close) || c.high < c.low
            }) return ValidatedSeries(emptyList(), AdaptiveReason.WAIT_DATA_INVALID, closed.lastOrNull()?.openTime)
        if (closed.size < 2) return ValidatedSeries(closed, AdaptiveReason.WAIT_INSUFFICIENT_HISTORY, closed.lastOrNull()?.openTime)
        if (closed.zipWithNext().any { (a, b) -> b.openTime <= a.openTime })
            return ValidatedSeries(closed, AdaptiveReason.WAIT_DATA_INVALID, closed.last().openTime)
        if (closed.zipWithNext().any { (a, b) -> b.openTime - a.openTime != interval })
            return ValidatedSeries(closed, AdaptiveReason.WAIT_DATA_GAP, closed.last().openTime)
        val latest = closed.last()
        if (decisionTime - (latest.closeTime + 1L) > interval * config.maxStalenessIntervals)
            return ValidatedSeries(closed, AdaptiveReason.WAIT_DATA_STALE, latest.openTime)
        return ValidatedSeries(closed, null, latest.openTime)
    }

    private data class RegimeMetrics(val atrPercent: Double, val expansion: Double, val efficiency: Double, val slopeAtr: Double)

    private fun regimeMetrics(candles: List<Candle>, config: AdaptiveEngineConfig): RegimeMetrics? {
        val ranges = candles.zipWithNext().map { (previous, current) ->
            max(current.high - current.low, max(abs(current.high - previous.close), abs(current.low - previous.close)))
        }
        if (ranges.size < config.atrPeriod + config.atrBaselinePeriod) return null
        val currentAtr = ranges.takeLast(config.atrPeriod).average()
        val baselineEnd = ranges.size - config.atrPeriod
        val baselineStart = baselineEnd - config.atrBaselinePeriod
        val baselineAtr = ranges.subList(baselineStart, baselineEnd).average()
        if (!currentAtr.isFinite() || !baselineAtr.isFinite() || currentAtr <= 0.0 || baselineAtr <= 0.0) return null
        val window = candles.takeLast(config.minBars)
        val netMove = abs(window.last().close - window.first().close)
        val path = window.zipWithNext().sumOf { (a, b) -> abs(b.close - a.close) }
        val efficiency = if (path > 0.0) (netMove / path).coerceIn(0.0, 1.0) else 0.0
        val slope = (window.last().close - window.first().close) / currentAtr
        return RegimeMetrics(currentAtr / window.last().close * 100.0, currentAtr / baselineAtr, efficiency, slope)
    }

    private fun directionalBias(candles: List<Candle>, config: AdaptiveEngineConfig): AdaptiveDecision {
        val atr = averageTrueRange(candles, config.atrPeriod) ?: return AdaptiveDecision.WAIT
        val window = candles.takeLast(config.minBars)
        val first = window.first().close
        val last = window.last().close
        val path = window.zipWithNext().sumOf { (a, b) -> abs(b.close - a.close) }
        val efficiency = if (path > 0.0) abs(last - first) / path else 0.0
        val slopeAtr = (last - first) / atr
        if (efficiency < config.minimumBiasEfficiency || abs(slopeAtr) < config.minimumBiasSlopeAtr)
            return AdaptiveDecision.WAIT
        return if (slopeAtr > 0.0) AdaptiveDecision.LONG else AdaptiveDecision.SHORT
    }

    private fun averageTrueRange(candles: List<Candle>, period: Int): Double? {
        if (candles.size < period + 1) return null
        val values = candles.zipWithNext().takeLast(period).map { (a, b) ->
            max(b.high - b.low, max(abs(b.high - a.close), abs(b.low - a.close)))
        }
        return values.average().takeIf { it.isFinite() && it > 0.0 }
    }

    private fun result(
        input: AdaptiveEngineInput, h1: List<Candle>, metrics: RegimeMetrics, regime: AdaptiveRegime,
        decision: AdaptiveDecision, reason: AdaptiveReason, explanation: String
    ) = AdaptiveEngineResult(
        decision, regime, reason, input.decisionTime, h1.lastOrNull()?.openTime,
        metrics.atrPercent, metrics.expansion, metrics.efficiency, metrics.slopeAtr, explanation
    )

    private fun waiting(time: Long, reason: AdaptiveReason, latest: Long?, explanation: String) =
        AdaptiveEngineResult(AdaptiveDecision.WAIT, AdaptiveRegime.UNKNOWN, reason, time, latest,
            null, null, null, null, explanation)
}
