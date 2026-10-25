package com.btcstructureanalyzer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class BinanceRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .build()

    suspend fun latestPrices(symbols: Set<String>): Map<String, Double> = withContext(Dispatchers.IO) {
        if (symbols.isEmpty()) return@withContext emptyMap()
        val paths = listOf(
            "https://data-api.binance.vision/api/v3/ticker/price",
            "https://api.binance.com/api/v3/ticker/price",
            "https://api1.binance.com/api/v3/ticker/price"
        )
        var lastError: Exception? = null
        for (url in paths) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "BTCStructureAnalyzer-Spot/0.5")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Binance ticker HTTP ${response.code}")
                    val array = JSONArray(response.body?.string().orEmpty())
                    val prices = buildMap {
                        for (i in 0 until array.length()) {
                            val row = array.optJSONObject(i) ?: continue
                            val symbol = row.optString("symbol")
                            if (symbol !in symbols) continue
                            val price = row.optString("price").toDoubleOrNull() ?: continue
                            if (price > 0.0) put(symbol, price)
                        }
                    }
                    if (prices.isEmpty()) throw IllegalStateException("Harga terbaru untuk koin riwayat tidak ditemukan")
                    return@withContext prices
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException(
            "Gagal mengambil harga terbaru Binance untuk evaluasi riwayat. Periksa jaringan/DNS. ${lastError?.message ?: ""}",
            lastError
        )
    }


    /** Finds the nearest completed 1m Spot candle close around a requested evaluation target. */
    suspend fun historicalSpotClose(symbol: String, targetTime: Long): Pair<Double, Long>? = withContext(Dispatchers.IO) {
        val minute = targetTime / 60_000L * 60_000L
        val start = (minute - 60_000L).coerceAtLeast(0L)
        val end = targetTime + 120_000L
        val paths = listOf(
            "https://data-api.binance.vision/api/v3/klines?symbol=$symbol&interval=1m&startTime=$start&endTime=$end&limit=4",
            "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=1m&startTime=$start&endTime=$end&limit=4",
            "https://api1.binance.com/api/v3/klines?symbol=$symbol&interval=1m&startTime=$start&endTime=$end&limit=4"
        )
        var lastError: Exception? = null
        for (url in paths) {
            try {
                val request = Request.Builder().url(url)
                    .header("User-Agent", "PredictionLab-SpotHistoricalEvaluation/1.0").get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Spot historical candle HTTP ${response.code}")
                    val rows = JSONArray(response.body?.string().orEmpty())
                    val candidates = buildList {
                        for (i in 0 until rows.length()) {
                            val row = rows.optJSONArray(i) ?: continue
                            if (row.length() < 7) continue
                            val close = row.optString(4).toDoubleOrNull() ?: continue
                            val closeTime = row.optLong(6, -1L)
                            // Accept only completed candle closes within 90 seconds of the target.
                            if (close > 0.0 && closeTime >= targetTime - 30_000L &&
                                closeTime <= targetTime + 90_000L) add(close to closeTime)
                        }
                    }
                    return@withContext candidates.minByOrNull { kotlin.math.abs(it.second - targetTime) }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (lastError != null) throw IllegalStateException("Gagal mengambil candle historis Spot untuk evaluasi: ${lastError.message}", lastError)
        null
    }

    suspend fun candles(interval: String, limit: Int = 200, symbol: String = "BTCUSDT"): List<Candle> = withContext(Dispatchers.IO) {
        require(interval in setOf("5m", "15m", "1h")) { "Timeframe tidak didukung: $interval" }
        // Preserve the original Spot market-data route that was used before the Futures experiment.
        // data-api.binance.vision is a public market-data REST endpoint; fall back to Spot API hosts.
        val paths = listOf(
            "https://data-api.binance.vision/api/v3/klines?symbol=$symbol&interval=$interval&limit=$limit",
            "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=$interval&limit=$limit",
            "https://api1.binance.com/api/v3/klines?symbol=$symbol&interval=$interval&limit=$limit"
        )
        var lastError: Exception? = null
        for (url in paths) {
            try {
                val request = Request.Builder().url(url).header("User-Agent", "BTCStructureAnalyzer-Spot/0.5").get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Server candle Spot membalas HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    val array = JSONArray(body)
                    if (array.length() == 0) throw IllegalStateException("Data candle Spot kosong")
                    return@withContext buildList {
                        for (i in 0 until array.length()) {
                            val row = array.getJSONArray(i)
                            add(Candle(row.getLong(0), row.getString(1).toDouble(), row.getString(2).toDouble(),
                                row.getString(3).toDouble(), row.getString(4).toDouble(), row.getString(5).toDouble(),
                                row.getLong(6)))
                        }
                    }
                }
            } catch (e: Exception) { lastError = e }
        }
        throw IllegalStateException("Gagal menghubungi endpoint Binance Spot. Periksa jaringan/DNS lalu coba lagi. ${lastError?.message ?: ""}", lastError)
    }
}