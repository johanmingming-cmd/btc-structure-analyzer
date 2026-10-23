package com.btcstructureanalyzer

data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long
)

enum class Trend { BULLISH, BEARISH, SIDEWAYS, UNCONFIRMED }
data class Swing(val index: Int, val price: Double, val isHigh: Boolean, val time: Long, val label: String)
data class StructureResult(
    val timeframe: String,
    val trend: Trend,
    val swings: List<Swing>,
    val summary: String,
    val lastUpdated: Long = System.currentTimeMillis()
)