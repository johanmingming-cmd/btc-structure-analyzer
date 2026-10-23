package com.btcstructureanalyzer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class BinanceRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun candles(interval: String, limit: Int = 200): List<Candle> = withContext(Dispatchers.IO) {
        require(interval in setOf("5m", "15m", "1h")) { "Unsupported interval" }
        val url = "https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=$interval&limit=$limit"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Binance HTTP ${response.code}" }
            val array = JSONArray(response.body?.string() ?: "[]")
            buildList {
                for (i in 0 until array.length()) {
                    val row = array.getJSONArray(i)
                    add(Candle(
                        openTime = row.getLong(0),
                        open = row.getString(1).toDouble(),
                        high = row.getString(2).toDouble(),
                        low = row.getString(3).toDouble(),
                        close = row.getString(4).toDouble(),
                        volume = row.getString(5).toDouble(),
                        closeTime = row.getLong(6)
                    ))
                }
            }
        }
    }
}