package com.btcstructureanalyzer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

data class ScannedCoin(
    val symbol: String,
    val price: Double,
    val quoteVolume: Double,
    val trend5m: Trend,
    val trend15m: Trend,
    val trend1h: Trend,
    val score: Int,
    val reason: String
)

class CoinScanner(private val repository: BinanceRepository) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .build()

    suspend fun scan(limit: Int = 10): List<ScannedCoin> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://data-api.binance.vision/api/v3/ticker/24hr")
            .header("User-Agent", "BTCStructureAnalyzer/0.3")
            .get().build()
        val tickers = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Gagal mengambil daftar market Binance (HTTP ${response.code})")
            JSONArray(response.body?.string().orEmpty())
        }
        val excluded = setOf("USDC", "FDUSD", "TUSD", "USDP", "DAI", "BUSD", "EUR", "TRY", "BRL", "GBP", "BIDR", "IDRT")
        val candidates = buildList {
            for (i in 0 until tickers.length()) {
                val row = tickers.getJSONObject(i)
                val symbol = row.optString("symbol")
                if (!symbol.endsWith("USDT") || symbol == "BTCUSDT") continue
                val base = symbol.removeSuffix("USDT")
                if (excluded.contains(base) || base.endsWith("UP") || base.endsWith("DOWN") ||
                    base.endsWith("BULL") || base.endsWith("BEAR") || base.endsWith("USDT")) continue
                val quoteVolume = row.optString("quoteVolume").toDoubleOrNull() ?: 0.0
                val lastPrice = row.optString("lastPrice").toDoubleOrNull() ?: 0.0
                if (quoteVolume >= 5_000_000.0 && lastPrice > 0.0) add(Triple(symbol, lastPrice, quoteVolume))
            }
        }.sortedByDescending { it.third }.take(40)

        val output = mutableListOf<ScannedCoin>()
        for ((symbol, lastPrice, quoteVolume) in candidates) {
            try {
                val c5 = repository.candles("5m", 80, symbol)
                val c15 = repository.candles("15m", 80, symbol)
                val c1h = repository.candles("1h", 80, symbol)
                val r5 = StructureAnalyzer.analyze("5m", c5)
                val r15 = StructureAnalyzer.analyze("15m", c15)
                val r1h = StructureAnalyzer.analyze("1h", c1h)
                val trends = listOf(r1h.trend, r15.trend, r5.trend)
                val confirmed = trends.filter { it == Trend.BULLISH || it == Trend.BEARISH }
                val direction = when {
                    confirmed.count { it == Trend.BULLISH } >= 2 -> Trend.BULLISH
                    confirmed.count { it == Trend.BEARISH } >= 2 -> Trend.BEARISH
                    else -> Trend.SIDEWAYS
                }
                val aligned = trends.count { it == direction }
                val volumeScore = (kotlin.math.ln(quoteVolume.coerceAtLeast(1.0)) * 2.0).toInt().coerceIn(0, 40)
                val score = (if (direction == Trend.SIDEWAYS) 15 else 35 + aligned * 12 + volumeScore / 4).coerceIn(0, 100)
                val reason = when {
                    direction == Trend.SIDEWAYS -> "Timeframe belum selaras"
                    aligned == 3 -> "Struktur 1H, 15M, dan 5M searah"
                    else -> "Mayoritas timeframe searah; tunggu konfirmasi"
                }
                output.add(ScannedCoin(symbol, lastPrice, quoteVolume, r5.trend, r15.trend, r1h.trend, score, reason))
            } catch (_: Exception) {
                // Skip a symbol whose data is unavailable; keep the scanner moving.
            }
            if (output.size >= limit) break
        }
        output.sortedWith(compareByDescending<ScannedCoin> { it.score }.thenByDescending { it.quoteVolume })
    }
}
