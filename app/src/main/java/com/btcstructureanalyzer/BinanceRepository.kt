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

    suspend fun candles(interval: String, limit: Int = 200): List<Candle> = withContext(Dispatchers.IO) {
        require(interval in setOf("5m", "15m", "1h")) { "Timeframe tidak didukung: $interval" }
        val paths = listOf(
            "https://data-api.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=$interval&limit=$limit",
            "https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=$interval&limit=$limit"
        )
        var lastError: Exception? = null
        for (url in paths) {
            try {
                val request = Request.Builder().url(url).header("User-Agent", "BTCStructureAnalyzer/0.2").get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Server candle membalas HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    val array = JSONArray(body)
                    if (array.length() == 0) throw IllegalStateException("Data candle kosong")
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
        throw IllegalStateException("Gagal menghubungi endpoint data Binance. Periksa jaringan/DNS lalu coba lagi. ${lastError?.message ?: ""}", lastError)
    }
}