package com.btcstructureanalyzer

/**
 * Confirmed market-structure baseline: 2 candles on each side.
 * The currently forming candle is excluded. Equal highs/lows are not forced
 * into HH/LH or HL/LL labels; they keep the structure neutral.
 */
object StructureAnalyzer {
    private const val LEFT = 2
    private const val RIGHT = 2


    data class ForecastStep(val direction: String, val label: String, val confidence: Int)
    data class ForecastResult(val summary: String, val steps: List<ForecastStep>)

    // Transparent heuristic scenario; not a trained ML model or validated probability.
    fun forecast(candles: List<Candle>, five: StructureResult?, fifteen: StructureResult?, oneHour: StructureResult?): ForecastResult {
        val closed = candles.dropLast(1).takeLast(8)
        if (closed.size < 5 || five == null || fifteen == null || oneHour == null ||
            five.trend == Trend.UNCONFIRMED || oneHour.trend == Trend.UNCONFIRMED) {
            return ForecastResult("Data/swing belum cukup; proyeksi ditahan agar tidak mengarang arah.",
                listOf(ForecastStep("NETRAL", "Netral", 0), ForecastStep("NETRAL", "Netral", 0), ForecastStep("NETRAL", "Netral", 0)))
        }
        val momentum = closed.takeLast(4).sumOf { (it.close - it.open) / it.open.coerceAtLeast(1.0) }
        var score = when (oneHour.trend) { Trend.BULLISH -> 2; Trend.BEARISH -> -2; else -> 0 }
        score += when (fifteen.trend) { Trend.BULLISH -> 1; Trend.BEARISH -> -1; else -> 0 }
        score += when (five.trend) { Trend.BULLISH -> 1; Trend.BEARISH -> -1; else -> 0 }
        score += when { momentum > 0.001 -> 1; momentum < -0.001 -> -1; else -> 0 }
        val direction = when { score >= 2 -> "NAIK"; score <= -2 -> "TURUN"; else -> "NETRAL" }
        val aligned = oneHour.trend == fifteen.trend && fifteen.trend == five.trend
        val confidence = (52 + kotlin.math.abs(score) * 5 + if (aligned) 4 else 0).coerceIn(50, 72)
        val label = when(direction) { "NAIK" -> "Naik"; "TURUN" -> "Turun"; else -> "Netral" }
        val summary = when(direction) {
            "NAIK" -> "Skenario naik lebih dominan selama support struktur tidak ditembus."
            "TURUN" -> "Skenario turun lebih dominan selama resistance struktur tidak direbut."
            else -> "Timeframe/momentum bercampur. Tunggu konfirmasi, jangan paksa arah."
        }
        return ForecastResult(summary, (0..2).map { i ->
            ForecastStep(direction, label, if (direction == "NETRAL") 50 else (confidence - i * 3).coerceIn(50, 72))
        })
    }

    fun analyze(timeframe: String, candles: List<Candle>): StructureResult {
        if (candles.size < LEFT + RIGHT + 3) {
            return StructureResult(timeframe, Trend.UNCONFIRMED, emptyList(), "Data candle belum cukup")
        }

        // Binance returns the newest (possibly still-open) candle last.
        val closedCount = candles.size - 1
        val confirmed = candles.take(closedCount)
        if (confirmed.size < LEFT + RIGHT + 1) {
            return StructureResult(timeframe, Trend.UNCONFIRMED, emptyList(), "Menunggu candle tertutup")
        }

        val pivots = mutableListOf<Swing>()
        for (i in LEFT until confirmed.size - RIGHT) {
            val current = confirmed[i]
            val left = confirmed.subList(i - LEFT, i)
            val right = confirmed.subList(i + 1, i + RIGHT + 1)
            val isHigh = left.all { current.high > it.high } && right.all { current.high > it.high }
            val isLow = left.all { current.low < it.low } && right.all { current.low < it.low }
            // A wide outside candle can technically qualify as both; skip ambiguous pivots.
            if (isHigh xor isLow) {
                pivots += Swing(i, if (isHigh) current.high else current.low, isHigh, current.openTime, "")
            }
        }

        val clean = pivots.sortedBy { it.index }.toMutableList()
        var previousHigh: Swing? = null
        var previousLow: Swing? = null
        for (j in clean.indices) {
            val s = clean[j]
            if (s.isHigh) {
                val prev = previousHigh
                val tolerance = maxOf(0.5, s.price * 0.00001)
                val label = when {
                    prev == null -> "H"
                    s.price > prev.price + tolerance -> "HH"
                    s.price < prev.price - tolerance -> "LH"
                    else -> "EQH"
                }
                clean[j] = s.copy(label = label)
                previousHigh = s
            } else {
                val prev = previousLow
                val tolerance = maxOf(0.5, s.price * 0.00001)
                val label = when {
                    prev == null -> "L"
                    s.price > prev.price + tolerance -> "HL"
                    s.price < prev.price - tolerance -> "LL"
                    else -> "EQL"
                }
                clean[j] = s.copy(label = label)
                previousLow = s
            }
        }

        val highs = clean.filter { it.isHigh }.takeLast(2)
        val lows = clean.filterNot { it.isHigh }.takeLast(2)
        val trend = when {
            highs.size < 2 || lows.size < 2 -> Trend.UNCONFIRMED
            highs.last().label == "HH" && lows.last().label == "HL" -> Trend.BULLISH
            highs.last().label == "LH" && lows.last().label == "LL" -> Trend.BEARISH
            else -> Trend.SIDEWAYS
        }
        val summary = when (trend) {
            Trend.BULLISH -> "Struktur naik: HH + HL terdeteksi"
            Trend.BEARISH -> "Struktur turun: LH + LL terdeteksi"
            Trend.SIDEWAYS -> "Struktur campuran atau high/low setara; arah belum jelas"
            Trend.UNCONFIRMED -> "Swing terkonfirmasi belum cukup untuk menentukan arah"
        }
        return StructureResult(timeframe, trend, clean.takeLast(12), summary)
    }
}