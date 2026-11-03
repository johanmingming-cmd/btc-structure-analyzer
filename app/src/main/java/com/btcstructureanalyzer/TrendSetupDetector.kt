package com.btcstructureanalyzer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class SetupFamily { TREND_CONTINUATION, TREND_PULLBACK, NONE }
enum class SetupStatus { CONFIRMED, WAIT }
enum class SetupWaitReason { REGIME_NOT_TREND, TIMEFRAME_DISAGREEMENT, INSUFFICIENT_DATA, NO_15M_PATTERN, NO_5M_TRIGGER }

data class SetupDetectionConfig(
    val atrPeriod: Int = 14,
    val emaPeriod: Int = 20,
    val breakoutLookback: Int = 10,
    val pullbackMaxDistanceAtr: Double = 0.65,
    val minimumBodyAtr: Double = 0.20,
    val minimumVolumeRatio: Double = 0.80
)

data class SetupDetectionResult(
    val family: SetupFamily,
    val status: SetupStatus,
    val direction: AdaptiveDecision,
    val waitReason: SetupWaitReason?,
    val qualityScore: Int,
    val explanation: String
)

/**
 * Conservative trend setup detector. Inputs must already be validated, ordered,
 * closed candles. This class never fetches data and never reads the forming candle.
 */
object TrendSetupDetector {
    fun detect(
        regime: AdaptiveRegime,
        bias1h: AdaptiveDecision,
        bias15m: AdaptiveDecision,
        candles15m: List<Candle>,
        candles5m: List<Candle>,
        config: SetupDetectionConfig = SetupDetectionConfig()
    ): SetupDetectionResult {
        if (regime != AdaptiveRegime.TREND) return wait(SetupWaitReason.REGIME_NOT_TREND)
        if (bias1h !in listOf(AdaptiveDecision.LONG, AdaptiveDecision.SHORT) || bias1h != bias15m)
            return wait(SetupWaitReason.TIMEFRAME_DISAGREEMENT)
        if (config.atrPeriod < 2 || config.emaPeriod < 2 || config.breakoutLookback < 3 ||
            !config.pullbackMaxDistanceAtr.isFinite() || config.pullbackMaxDistanceAtr <= 0.0 ||
            !config.minimumBodyAtr.isFinite() || config.minimumBodyAtr < 0.0 ||
            !config.minimumVolumeRatio.isFinite() || config.minimumVolumeRatio < 0.0) {
            return wait(SetupWaitReason.INSUFFICIENT_DATA)
        }
        if (candles15m.size < max(config.emaPeriod, config.atrPeriod + config.breakoutLookback + 1) ||
            candles5m.size < config.atrPeriod + 3) return wait(SetupWaitReason.INSUFFICIENT_DATA)
        if (!validClosedCandles(candles15m) || !validClosedCandles(candles5m))
            return wait(SetupWaitReason.INSUFFICIENT_DATA)

        val atr15 = atr(candles15m, config.atrPeriod) ?: return wait(SetupWaitReason.INSUFFICIENT_DATA)
        val ema = ema(candles15m, config.emaPeriod)
        val last15 = candles15m.last()
        val prior15 = candles15m.dropLast(1).takeLast(config.breakoutLookback)
        val isLong = bias1h == AdaptiveDecision.LONG
        val breakout = if (isLong) last15.close > prior15.maxOf { it.high } else last15.close < prior15.minOf { it.low }
        val directionalBody = if (isLong) last15.close > last15.open else last15.close < last15.open
        val body15 = abs(last15.close - last15.open)
        val nearEma = abs(last15.close - ema) <= atr15 * config.pullbackMaxDistanceAtr
        val pullbackShape = nearEma && directionalBody && body15 >= atr15 * config.minimumBodyAtr

        val family = when {
            breakout && directionalBody && body15 >= atr15 * config.minimumBodyAtr -> SetupFamily.TREND_CONTINUATION
            pullbackShape -> SetupFamily.TREND_PULLBACK
            else -> return wait(SetupWaitReason.NO_15M_PATTERN)
        }

        val atr5 = atr(candles5m, config.atrPeriod) ?: return wait(SetupWaitReason.INSUFFICIENT_DATA)
        val last5 = candles5m.last()
        val previous5 = candles5m.dropLast(1).takeLast(2)
        val trigger = if (isLong) last5.close > previous5.maxOf { it.high } else last5.close < previous5.minOf { it.low }
        val triggerBody = abs(last5.close - last5.open) >= atr5 * config.minimumBodyAtr
        val avgVolume = previous5.map { it.volume }.average()
        val volumeRatio = if (avgVolume > 0.0) last5.volume / avgVolume else 0.0
        if (!trigger || !triggerBody || volumeRatio < config.minimumVolumeRatio)
            return wait(SetupWaitReason.NO_5M_TRIGGER)

        val bodyStrength = (abs(last15.close - last15.open) / atr15).coerceIn(0.0, 1.0)
        val volumeStrength = (volumeRatio / 2.0).coerceIn(0.0, 1.0)
        val score = (60 + bodyStrength * 20 + volumeStrength * 20).toInt().coerceIn(60, 100)
        val direction = if (isLong) AdaptiveDecision.LONG else AdaptiveDecision.SHORT
        val label = if (family == SetupFamily.TREND_CONTINUATION) "continuation" else "pullback"
        return SetupDetectionResult(
            family, SetupStatus.CONFIRMED, direction, null, score,
            "Setup $label $direction terkonfirmasi: struktur 1H/15M searah, pola 15M dan trigger candle 5M tertutup mendukung."
        )
    }

    private fun wait(reason: SetupWaitReason) = SetupDetectionResult(
        SetupFamily.NONE, SetupStatus.WAIT, AdaptiveDecision.WAIT, reason, 0,
        "Belum ada setup terkonfirmasi: ${reason.name}."
    )

    private fun validClosedCandles(candles: List<Candle>): Boolean =
        candles.all { c ->
            c.openTime >= 0L && c.closeTime > c.openTime &&
                c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite() &&
                c.volume.isFinite() && c.open > 0.0 && c.high >= max(c.open, c.close) &&
                c.low <= min(c.open, c.close) && c.low > 0.0 && c.volume >= 0.0
        } && candles.zipWithNext().all { (previous, current) ->
            current.openTime > previous.openTime && current.closeTime > previous.closeTime
        }

    private fun atr(candles: List<Candle>, period: Int): Double? {
        if (candles.size < period + 1) return null
        val values = candles.zipWithNext().takeLast(period).map { (previous, current) ->
            max(current.high - current.low, max(abs(current.high - previous.close), abs(current.low - previous.close)))
        }
        val result = values.average()
        return result.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun ema(candles: List<Candle>, period: Int): Double {
        require(candles.size >= period)
        val seed = candles.take(period).map { it.close }.average()
        val alpha = 2.0 / (period + 1.0)
        return candles.drop(period).fold(seed) { value, candle ->
            alpha * candle.close + (1.0 - alpha) * value
        }
    }
}
