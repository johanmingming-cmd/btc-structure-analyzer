package com.btcstructureanalyzer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class RangeSetupFamily { RANGE_SUPPORT_REJECTION, RANGE_RESISTANCE_REJECTION, NONE }
enum class RangeWaitReason { REGIME_NOT_RANGE, INSUFFICIENT_DATA, NO_BOUNDARY_REJECTION, NO_5M_CONFIRMATION }

data class RangeDetectionConfig(
    val atrPeriod: Int = 14,
    val boundaryLookback: Int = 24,
    val boundaryAtrTolerance: Double = 0.20,
    val minimumWickBodyRatio: Double = 1.25,
    val minimumVolumeRatio: Double = 0.75,
    val minimumBodyAtr: Double = 0.10
)

data class RangeDetectionResult(
    val family: RangeSetupFamily,
    val status: SetupStatus,
    val direction: AdaptiveDecision,
    val waitReason: RangeWaitReason?,
    val qualityScore: Int,
    val explanation: String,
    val diagnosticDetail: String? = null
)

/** Reversal is allowed only after a shallow boundary sweep/rejection and a separate closed 5m trigger. */
object RangeReversalDetector {
    fun detect(
        regime: AdaptiveRegime,
        candles15m: List<Candle>,
        candles5m: List<Candle>,
        config: RangeDetectionConfig = RangeDetectionConfig()
    ): RangeDetectionResult {
        if (regime != AdaptiveRegime.RANGE) return wait(RangeWaitReason.REGIME_NOT_RANGE)
        if (config.atrPeriod < 2 || config.boundaryLookback < 5 ||
            !config.boundaryAtrTolerance.isFinite() || config.boundaryAtrTolerance <= 0.0 ||
            !config.minimumWickBodyRatio.isFinite() || config.minimumWickBodyRatio <= 0.0 ||
            !config.minimumVolumeRatio.isFinite() || config.minimumVolumeRatio < 0.0 ||
            !config.minimumBodyAtr.isFinite() || config.minimumBodyAtr < 0.0) {
            return wait(RangeWaitReason.INSUFFICIENT_DATA)
        }
        if (candles15m.size < max(config.atrPeriod + 1, config.boundaryLookback + 1) ||
            candles5m.size < config.atrPeriod + 3 ||
            !valid(candles15m) || !valid(candles5m)) return wait(RangeWaitReason.INSUFFICIENT_DATA)

        val atr15 = atr(candles15m, config.atrPeriod) ?: return wait(RangeWaitReason.INSUFFICIENT_DATA)
        val prior = candles15m.dropLast(1).takeLast(config.boundaryLookback)
        val support = prior.minOf { it.low }
        val resistance = prior.maxOf { it.high }
        val last = candles15m.last()
        val body = max(abs(last.close - last.open), atr15 * 0.01)
        val lowerWick = min(last.open, last.close) - last.low
        val upperWick = last.high - max(last.open, last.close)
        val avgVolume = prior.takeLast(min(10, prior.size)).map { it.volume }.average()
        val volumeRatio = if (avgVolume > 0.0) last.volume / avgVolume else 0.0
        val maxSweep = atr15 * config.boundaryAtrTolerance

        val supportSwept = last.low < support
        val resistanceSwept = last.high > resistance
        val supportDepthOk = support - last.low <= maxSweep
        val resistanceDepthOk = last.high - resistance <= maxSweep
        val supportReclaimed = last.close > support
        val resistanceReclaimed = last.close < resistance
        val supportWickOk = lowerWick / body >= config.minimumWickBodyRatio
        val resistanceWickOk = upperWick / body >= config.minimumWickBodyRatio
        val bodyOk = abs(last.close - last.open) >= atr15 * config.minimumBodyAtr
        val supportReject = supportSwept && supportDepthOk && supportReclaimed && supportWickOk && bodyOk
        val resistanceReject = resistanceSwept && resistanceDepthOk && resistanceReclaimed && resistanceWickOk && bodyOk
        if (supportReject == resistanceReject) {
            val detail = when {
                supportReject && resistanceReject -> "AMBIGUOUS_BOTH_BOUNDARIES_REJECTED"
                supportSwept && resistanceSwept -> "BOTH_BOUNDARIES_SWEPT"
                !supportSwept && !resistanceSwept -> "NO_BOUNDARY_SWEEP"
                supportSwept && !supportDepthOk -> "SUPPORT_SWEEP_TOO_DEEP"
                resistanceSwept && !resistanceDepthOk -> "RESISTANCE_SWEEP_TOO_DEEP"
                supportSwept && !supportReclaimed -> "SUPPORT_CLOSE_FAILED_RECLAIM"
                resistanceSwept && !resistanceReclaimed -> "RESISTANCE_CLOSE_FAILED_RECLAIM"
                supportSwept && !supportWickOk -> "SUPPORT_WICK_BODY_RATIO_TOO_LOW"
                resistanceSwept && !resistanceWickOk -> "RESISTANCE_WICK_BODY_RATIO_TOO_LOW"
                !bodyOk -> "REJECTION_BODY_TOO_SMALL"
                else -> "BOUNDARY_REJECTION_NOT_UNIQUE"
            }
            return wait(RangeWaitReason.NO_BOUNDARY_REJECTION, detail)
        }
        if (volumeRatio < config.minimumVolumeRatio)
            return wait(RangeWaitReason.NO_BOUNDARY_REJECTION, "VOLUME_RATIO_TOO_LOW")

        val direction = if (supportReject) AdaptiveDecision.LONG else AdaptiveDecision.SHORT
        val atr5 = atr(candles5m, config.atrPeriod) ?: return wait(RangeWaitReason.INSUFFICIENT_DATA)
        val triggerBars = candles5m.dropLast(1).takeLast(2)
        val last5 = candles5m.last()
        val trigger = if (direction == AdaptiveDecision.LONG)
            last5.close > triggerBars.maxOf { it.high } && last5.close > last5.open
        else last5.close < triggerBars.minOf { it.low } && last5.close < last5.open
        if (!trigger) return wait(RangeWaitReason.NO_5M_CONFIRMATION, "FIVE_MINUTE_TRIGGER_NOT_MET")
        if (abs(last5.close - last5.open) < atr5 * config.minimumBodyAtr)
            return wait(RangeWaitReason.NO_5M_CONFIRMATION, "FIVE_MINUTE_TRIGGER_BODY_TOO_SMALL")

        val family = if (supportReject) RangeSetupFamily.RANGE_SUPPORT_REJECTION
            else RangeSetupFamily.RANGE_RESISTANCE_REJECTION
        val wickStrength = (max(lowerWick, upperWick) / body / 3.0).coerceIn(0.0, 1.0)
        val volumeStrength = (volumeRatio / 1.5).coerceIn(0.0, 1.0)
        val score = (60 + wickStrength * 20 + volumeStrength * 20).toInt().coerceIn(60, 100)
        return RangeDetectionResult(
            family, SetupStatus.CONFIRMED, direction, null, score,
            "Rejection batas range dan konfirmasi candle 5M tertutup searah: $direction."
        )
    }

    private fun wait(reason: RangeWaitReason, detail: String = reason.name) = RangeDetectionResult(
        RangeSetupFamily.NONE, SetupStatus.WAIT, AdaptiveDecision.WAIT, reason, 0,
        "Belum ada reversal range terkonfirmasi: ${reason.name} / $detail.", detail
    )

    private fun valid(candles: List<Candle>) = candles.all { c ->
        c.openTime >= 0L && c.closeTime > c.openTime &&
            c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite() &&
            c.volume.isFinite() && c.open > 0.0 && c.low > 0.0 &&
            c.high >= max(c.open, c.close) && c.low <= min(c.open, c.close) && c.high >= c.low && c.volume >= 0.0
    } && candles.zipWithNext().all { (previous, current) ->
        current.openTime > previous.openTime && current.closeTime > previous.closeTime
    }

    private fun atr(candles: List<Candle>, period: Int): Double? {
        if (candles.size < period + 1) return null
        val values = candles.zipWithNext().takeLast(period).map { (a, b) ->
            max(b.high - b.low, max(abs(b.high - a.close), abs(b.low - a.close)))
        }
        return values.average().takeIf { it.isFinite() && it > 0.0 }
    }
}
