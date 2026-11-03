package com.btcstructureanalyzer

import org.junit.Assert.assertEquals
import org.junit.Test

class StructureAnalyzerForecastTest {
    private fun result(timeframe: String, trend: Trend) =
        StructureResult(timeframe, trend, emptyList(), "test")

    @Test fun momentumUsesRelativeChangeForSubOneDollarAssets() {
        val candles = (0 until 9).map { index ->
            val open = 0.1
            val close = if (index >= 5) 0.1002 else 0.1
            Candle(index * 300_000L, open, close, open, close, 100.0, index * 300_000L + 299_999L)
        }
        val forecast = StructureAnalyzer.forecast(
            candles,
            result("5m", Trend.BEARISH),
            result("15m", Trend.BULLISH),
            result("1h", Trend.BULLISH)
        )
        assertEquals("NAIK", forecast.steps.first().direction)
        assertEquals(67, forecast.steps.first().score)
    }

    @Test fun unconfirmedFifteenMinuteTrendHoldsForecast() {
        val candles = (0 until 9).map { index ->
            Candle(index * 300_000L, 100.0, 101.0, 99.0, 100.5, 10.0, index * 300_000L + 299_999L)
        }
        val forecast = StructureAnalyzer.forecast(
            candles,
            result("5m", Trend.BULLISH),
            result("15m", Trend.UNCONFIRMED),
            result("1h", Trend.BULLISH)
        )
        assertEquals("NETRAL", forecast.steps.first().direction)
        assertEquals(0, forecast.steps.first().score)
    }
}
