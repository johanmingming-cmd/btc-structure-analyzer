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

    suspend fun scan(limit: Int = 15): List<ScannedCoin> = withContext(Dispatchers.IO) {
        var tickerData: JSONArray? = null
        var tickerError: Exception? = null
        for (endpoint in listOf("https://api.binance.com/api/v3/ticker/24hr", "https://api1.binance.com/api/v3/ticker/24hr")) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", "BTCStructureAnalyzer-Spot/0.5")
                    .get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Market Spot HTTP ${response.code}")
                    tickerData = JSONArray(response.body?.string().orEmpty())
                }
                if (tickerData != null) break
            } catch (e: Exception) {
                tickerError = e
            }
        }
        val tickers = tickerData ?: throw IllegalStateException(
            "Gagal mengambil daftar market Binance Spot. Periksa jaringan/DNS. ${tickerError?.message ?: ""}",
            tickerError
        )
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

                // Spot market scanner: higher-timeframe structure must agree.
                val sameBullish = r1h.trend == Trend.BULLISH && r15.trend == Trend.BULLISH
                val sameBearish = r1h.trend == Trend.BEARISH && r15.trend == Trend.BEARISH
                if (!sameBullish && !sameBearish) continue

                // Confirmation uses only closed 5m candles; ignore the currently forming candle.
                val closed5m = c5.dropLast(1).takeLast(3)
                if (closed5m.size < 3) continue
                val netMove = closed5m.last().close - closed5m.first().open
                val lastCandle = closed5m.last()
                val momentumConfirmed = if (sameBullish) {
                    netMove > 0.0 && lastCandle.close > lastCandle.open
                } else {
                    netMove < 0.0 && lastCandle.close < lastCandle.open
                }
                if (!momentumConfirmed) continue

                val volumeScore = (kotlin.math.ln(quoteVolume.coerceAtLeast(1.0)) * 2.0).toInt().coerceIn(0, 40)
                val score = (60 + volumeScore / 4 + if (lastCandle.volume >= closed5m.dropLast(1).map { it.volume }.average()) 5 else 0).coerceIn(60, 85)
                val directionText = if (sameBullish) "bullish" else "bearish"
                val reason = "Konfirmasi: struktur Spot 1H + 15M searah $directionText, momentum candle 5M tertutup mendukung"
                output.add(ScannedCoin(symbol, lastPrice, quoteVolume, r5.trend, r15.trend, r1h.trend, score, reason))
            } catch (_: Exception) {
                // Skip a symbol whose data is unavailable; keep the scanner moving.
            }
        }
        output.sortedWith(compareByDescending<ScannedCoin> { it.score }.thenByDescending { it.quoteVolume }).take(limit.coerceAtLeast(1))
    }
}