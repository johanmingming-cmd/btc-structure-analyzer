package com.btcstructureanalyzer

/**
 * Deterministic pivot 2-left / 2-right structure baseline.
 * Swings are confirmed only when the two right-hand candles are closed.
 */
object StructureAnalyzer {
    fun analyze(timeframe: String, candles: List<Candle>): StructureResult {
        if (candles.size < 7) return StructureResult(timeframe, Trend.UNCONFIRMED, emptyList(), "Data candle belum cukup")
        val pivots = mutableListOf<Swing>()
        val lastClosed = candles.size - 2
        for (i in 2 until lastClosed - 1) {
            if (i + 2 > lastClosed) break
            val current = candles[i]
            val left = candles.subList(i - 2, i)
            val right = candles.subList(i + 1, i + 3)
            val isHigh = left.all { current.high > it.high } && right.all { current.high > it.high }
            val isLow = left.all { current.low < it.low } && right.all { current.low < it.low }
            if (isHigh) pivots.add(Swing(i, current.high, true, current.openTime, ""))
            if (isLow) pivots.add(Swing(i, current.low, false, current.openTime, ""))
        }
        val clean = pivots.groupBy { it.index }.values.flatMap { group ->
            if (group.size > 1) emptyList() else group
        }.sortedBy { it.index }.toMutableList()
        var previousHigh: Swing? = null
        var previousLow: Swing? = null
        for (j in clean.indices) {
            val s = clean[j]
            if (s.isHigh) {
                val label = previousHigh?.let { if (s.price > it.price) "HH" else "LH" } ?: "H"
                clean[j] = s.copy(label = label)
                previousHigh = s
            } else {
                val label = previousLow?.let { if (s.price > it.price) "HL" else "LL" } ?: "L"
                clean[j] = s.copy(label = label)
                previousLow = s
            }
        }
        val highs = clean.filter { it.isHigh }.takeLast(2)
        val lows = clean.filter { !it.isHigh }.takeLast(2)
        val trend = when {
            highs.size >= 2 && lows.size >= 2 && highs.last().label == "HH" && lows.last().label == "HL" -> Trend.BULLISH
            highs.size >= 2 && lows.size >= 2 && highs.last().label == "LH" && lows.last().label == "LL" -> Trend.BEARISH
            else -> Trend.SIDEWAYS
        }
        val summary = when (trend) {
            Trend.BULLISH -> "Struktur naik: HH + HL terdeteksi"
            Trend.BEARISH -> "Struktur turun: LH + LL terdeteksi"
            Trend.SIDEWAYS -> "Struktur campuran; arah belum jelas"
            Trend.UNCONFIRMED -> "Menunggu data candle yang cukup"
        }
        return StructureResult(timeframe, trend, clean.takeLast(12), summary)
    }
}