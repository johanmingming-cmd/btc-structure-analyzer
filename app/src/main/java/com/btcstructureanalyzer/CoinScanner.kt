package com.btcstructureanalyzer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ScannedCoin(
    val symbol: String,
    val price: Double,
    val quoteVolume: Double,
    val trend5m: Trend,
    val trend15m: Trend,
    val trend1h: Trend,
    val score: Int,
    val reason: String,
    val supportLevels: List<Double> = emptyList(),
    val resistanceLevels: List<Double> = emptyList()
)

data class ScanDiagnostics(
    val spotPairs: Int = 0,
    val futuresWhitelist: Int = 0,
    val liquidCandidates: Int = 0,
    val candlesChecked: Int = 0,
    val trendConfirmed: Int = 0,
    val momentumConfirmed: Int = 0,
    val dataErrors: Int = 0,
    val bullishTrendPairs: Int = 0,
    val bearishTrendPairs: Int = 0,
    val unalignedTrendPairs: Int = 0,
    val momentumRejected: Int = 0,
    val oneHourBullish: Int = 0,
    val oneHourBearish: Int = 0,
    val oneHourSideways: Int = 0,
    val oneHourUnconfirmed: Int = 0,
    val fifteenMinuteBullish: Int = 0,
    val fifteenMinuteBearish: Int = 0,
    val fifteenMinuteSideways: Int = 0,
    val fifteenMinuteUnconfirmed: Int = 0,
    val candidateTrendDetails: List<String> = emptyList(),
    val lastError: String? = null
)

class CoinScanner(private val repository: BinanceRepository, private val context: android.content.Context) {
    var lastDiagnostics: ScanDiagnostics = ScanDiagnostics()
        private set
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .build()

    suspend fun scan(limit: Int = 15): List<ScannedCoin> = withContext(Dispatchers.IO) {
        var tickerData: JSONArray? = null
        var tickerError: Exception? = null
        for (endpoint in listOf("https://data-api.binance.vision/api/v3/ticker/24hr", "https://api.binance.com/api/v3/ticker/24hr", "https://api1.binance.com/api/v3/ticker/24hr")) {
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
        val tickers = tickerData ?: run {
            lastDiagnostics = ScanDiagnostics(lastError = "Spot API: ${tickerError?.message ?: "gagal mengambil ticker"}")
            throw IllegalStateException(
                "Gagal mengambil daftar market Binance Spot. Periksa jaringan/DNS. ${tickerError?.message ?: ""}",
                tickerError
            )
        }
        val currentPrices = buildMap {
            for (i in 0 until tickers.length()) {
                val row = tickers.optJSONObject(i) ?: continue
                val symbol = row.optString("symbol")
                val current = row.optString("lastPrice").toDoubleOrNull() ?: continue
                if (symbol.endsWith("USDT") && current > 0.0) put(symbol, current)
            }
        }
        // Live ticker is only for entry status. Historical evaluation uses completed Spot 1m candles.
        SignalJournal.updatePrices(context, currentPrices)

        // Fetch a small bounded batch with limited concurrency so historical lookups do not
        // block the scan for many serial network timeouts. Apply journal writes sequentially
        // afterward because the journal is stored as one SharedPreferences JSON array.
        val pending = SignalJournal.pendingEvaluations(context).take(MAX_HISTORICAL_EVALUATIONS_PER_SCAN)
        val historicalResults = coroutineScope {
            val permits = Semaphore(HISTORICAL_LOOKUP_CONCURRENCY)
            pending.map { item ->
                async(Dispatchers.IO) {
                    permits.withPermit {
                        val found = runCatching {
                            repository.historicalSpotClose(item.symbol, item.targetTime)
                        }.getOrNull()
                        item to found
                    }
                }
            }.map { it.await() }
        }
        historicalResults.forEach { (item, historical) ->
            if (historical != null) {
                SignalJournal.updateHistoricalEvaluation(
                    context = context,
                    signalId = item.signalId,
                    horizonMinutes = item.horizonMinutes,
                    price = historical.first,
                    candleCloseTime = historical.second
                )
            }
        }

        var candlesChecked = 0
        var trendConfirmed = 0
        var momentumConfirmedCount = 0
        var dataErrors = 0
        var bullishTrendPairs = 0
        var bearishTrendPairs = 0
        var unalignedTrendPairs = 0
        var momentumRejected = 0
        var oneHourBullish = 0
        var oneHourBearish = 0
        var oneHourSideways = 0
        var oneHourUnconfirmed = 0
        var fifteenMinuteBullish = 0
        var fifteenMinuteBearish = 0
        var fifteenMinuteSideways = 0
        var fifteenMinuteUnconfirmed = 0
        val candidateTrendDetails = mutableListOf<String>()
        // Only include USDT perpetual contracts currently tradable on Binance USDⓈ-M Futures.
        val futuresSymbols = try { fetchTradableUsdtPerpetualSymbols() } catch (e: Exception) {
            lastDiagnostics = ScanDiagnostics(spotPairs = tickers.length(), lastError = "Futures whitelist: ${e.message ?: "gagal mengambil daftar"}")
            throw e
        }
        val excluded = setOf("USDC", "FDUSD", "TUSD", "USDP", "DAI", "BUSD", "EUR", "TRY", "BRL", "GBP", "BIDR", "IDRT")
        var eligibleBeforeCap = 0
        val candidates = buildList {
            for (i in 0 until tickers.length()) {
                val row = tickers.getJSONObject(i)
                val symbol = row.optString("symbol")
                if (!symbol.endsWith("USDT") || symbol == "BTCUSDT" || symbol !in futuresSymbols) continue
                val base = symbol.removeSuffix("USDT")
                if (excluded.contains(base) || base.endsWith("UP") || base.endsWith("DOWN") ||
                    base.endsWith("BULL") || base.endsWith("BEAR") || base.endsWith("USDT")) continue
                val quoteVolume = row.optString("quoteVolume").toDoubleOrNull() ?: 0.0
                val lastPrice = row.optString("lastPrice").toDoubleOrNull() ?: 0.0
                val changePercent = row.optString("priceChangePercent").toDoubleOrNull() ?: 999.0
                val bid = row.optString("bidPrice").toDoubleOrNull() ?: 0.0
                val ask = row.optString("askPrice").toDoubleOrNull() ?: 0.0
                val spreadPercent = if (bid > 0.0 && ask >= bid) ((ask - bid) / ((ask + bid) / 2.0)) * 100.0 else 999.0
                // Initial anti-microcap / anti-pump safeguards; these are filters, not safety guarantees.
                if (quoteVolume >= 20_000_000.0 && lastPrice > 0.0 &&
                    kotlin.math.abs(changePercent) <= 12.0 && spreadPercent <= 0.50) {
                    eligibleBeforeCap++
                    add(Triple(symbol, lastPrice, quoteVolume))
                }
            }
        }.sortedByDescending { it.third }.take(40)

        val output = mutableListOf<ScannedCoin>()
        for ((symbol, lastPrice, quoteVolume) in candidates) {
            try {
                val c5 = repository.candles("5m", 80, symbol)
                val c15 = repository.candles("15m", 80, symbol)
                val c1h = repository.candles("1h", 80, symbol)
                candlesChecked++
                val r5 = StructureAnalyzer.analyze("5m", c5)
                val r15 = StructureAnalyzer.analyze("15m", c15)
                val r1h = StructureAnalyzer.analyze("1h", c1h)

                when (r1h.trend) {
                    Trend.BULLISH -> oneHourBullish++
                    Trend.BEARISH -> oneHourBearish++
                    Trend.SIDEWAYS -> oneHourSideways++
                    Trend.UNCONFIRMED -> oneHourUnconfirmed++
                }
                when (r15.trend) {
                    Trend.BULLISH -> fifteenMinuteBullish++
                    Trend.BEARISH -> fifteenMinuteBearish++
                    Trend.SIDEWAYS -> fifteenMinuteSideways++
                    Trend.UNCONFIRMED -> fifteenMinuteUnconfirmed++
                }
                candidateTrendDetails.add("$symbol • 1H: ${r1h.trend.name} • 15M: ${r15.trend.name}")
                val sameBullish = r1h.trend == Trend.BULLISH && r15.trend == Trend.BULLISH
                val sameBearish = r1h.trend == Trend.BEARISH && r15.trend == Trend.BEARISH
                when {
                    sameBullish -> bullishTrendPairs++
                    sameBearish -> bearishTrendPairs++
                    else -> unalignedTrendPairs++
                }
                if (!sameBullish && !sameBearish) continue
                trendConfirmed++

                val closed5m = c5.dropLast(1).takeLast(3)
                if (closed5m.size < 3) continue
                val netMove = closed5m.last().close - closed5m.first().open
                val lastCandle = closed5m.last()
                val momentumConfirmed = if (sameBullish) {
                    netMove > 0.0 && lastCandle.close > lastCandle.open
                } else {
                    netMove < 0.0 && lastCandle.close < lastCandle.open
                }
                if (!momentumConfirmed) {
                    momentumRejected++
                    continue
                }
                momentumConfirmedCount++

                val volumeScore = (kotlin.math.ln(quoteVolume.coerceAtLeast(1.0)) * 2.0).toInt().coerceIn(0, 40)
                val score = (60 + volumeScore / 4 + if (lastCandle.volume >= closed5m.dropLast(1).map { it.volume }.average()) 5 else 0).coerceIn(60, 85)
                val directionText = if (sameBullish) "bullish" else "bearish"
                val reason = "Konfirmasi: struktur Spot 1H + 15M searah $directionText, momentum candle 5M tertutup mendukung"
                val supports = r15.swings.asSequence()
                    .filter { !it.isHigh && it.price < lastPrice }
                    .map { it.price }
                    .distinctBy { (it * 100.0).toLong() }
                    .sortedDescending()
                    .take(3)
                    .toList()
                val resistances = r15.swings.asSequence()
                    .filter { it.isHigh && it.price > lastPrice }
                    .map { it.price }
                    .distinctBy { (it * 100.0).toLong() }
                    .sorted()
                    .take(3)
                    .toList()
                output.add(ScannedCoin(symbol, lastPrice, quoteVolume, r5.trend, r15.trend, r1h.trend, score, reason, supports, resistances))
            } catch (_: Exception) {
                dataErrors++
            }
        }
        lastDiagnostics = ScanDiagnostics(
            spotPairs = tickers.length(),
            futuresWhitelist = futuresSymbols.size,
            liquidCandidates = eligibleBeforeCap,
            candlesChecked = candlesChecked,
            trendConfirmed = trendConfirmed,
            momentumConfirmed = momentumConfirmedCount,
            dataErrors = dataErrors,
            bullishTrendPairs = bullishTrendPairs,
            bearishTrendPairs = bearishTrendPairs,
            unalignedTrendPairs = unalignedTrendPairs,
            momentumRejected = momentumRejected,
            oneHourBullish = oneHourBullish,
            oneHourBearish = oneHourBearish,
            oneHourSideways = oneHourSideways,
            oneHourUnconfirmed = oneHourUnconfirmed,
            fifteenMinuteBullish = fifteenMinuteBullish,
            fifteenMinuteBearish = fifteenMinuteBearish,
            fifteenMinuteSideways = fifteenMinuteSideways,
            fifteenMinuteUnconfirmed = fifteenMinuteUnconfirmed,
            candidateTrendDetails = candidateTrendDetails.toList()
        )
        val finalResults = output.sortedWith(compareByDescending<ScannedCoin> { it.score }.thenByDescending { it.quoteVolume }).take(limit.coerceAtLeast(1))
        SignalJournal.recordSignals(context, finalResults)
        finalResults
    }

    private fun fetchTradableUsdtPerpetualSymbols(): Set<String> {
        // Futures is only a reference list; market data and analysis remain on Binance Spot REST.
        val prefs = context.getSharedPreferences("btc_structure", android.content.Context.MODE_PRIVATE)
        val cacheKey = "futures_usdt_perpetual_symbols"
        val timeKey = "futures_usdt_perpetual_symbols_saved_at"
        var lastError: Exception? = null
        for (endpoint in listOf("https://fapi.binance.com/fapi/v1/exchangeInfo", "https://fapi1.binance.com/fapi/v1/exchangeInfo")) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", "BTCStructureAnalyzer-SpotFuturesFilter/0.7")
                    .get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Binance Futures exchangeInfo HTTP ${response.code}")
                    val json = JSONObject(response.body?.string().orEmpty())
                    val symbols = json.optJSONArray("symbols") ?: JSONArray()
                    val fresh = buildSet {
                        for (i in 0 until symbols.length()) {
                            val item = symbols.optJSONObject(i) ?: continue
                            if (item.optString("status") == "TRADING" &&
                                item.optString("contractType") == "PERPETUAL" &&
                                item.optString("quoteAsset") == "USDT") {
                                add(item.optString("symbol"))
                            }
                        }
                    }
                    if (fresh.isEmpty()) throw IllegalStateException("Daftar Futures kosong")
                    prefs.edit().putString(cacheKey, fresh.sorted().joinToString(","))
                        .putLong(timeKey, System.currentTimeMillis()).apply()
                    return fresh
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        val cached = prefs.getString(cacheKey, null).orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val savedAt = prefs.getLong(timeKey, 0L)
        val cacheAge = System.currentTimeMillis() - savedAt
        if (cached.isNotEmpty() && savedAt > 0L && cacheAge in 0..TimeUnit.DAYS.toMillis(30)) {
            return cached
        }

        throw IllegalStateException(
            "Daftar Futures belum tersedia dan belum ada cache valid. Sambungkan VPN sekali untuk menyimpan daftar acuan; setelah itu scan Spot bisa berjalan tanpa mengakses endpoint Futures setiap kali. ${lastError?.message ?: ""}",
            lastError
        )
    }

    private companion object {
        const val MAX_HISTORICAL_EVALUATIONS_PER_SCAN = 12
        const val HISTORICAL_LOOKUP_CONCURRENCY = 4
    }
}
