package com.btcstructureanalyzer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PendingEvaluation(val signalId: String, val symbol: String, val horizonMinutes: Int, val targetTime: Long)

data class HistoricalBar(val openTime: Long, val high: Double, val low: Double, val closeTime: Long)
data class TradeOutcome(val status: String, val eventTime: Long? = null)

data class SignalRecord(
    val id: String,
    val time: Long,
    val symbol: String,
    val direction: String,
    val price: Double,
    val entryLow: Double,
    val entryHigh: Double,
    val stopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    val score: Int,
    val supports: List<Double>,
    val resistances: List<Double>,
    val price15m: Double? = null,
    val price30m: Double? = null,
    val price60m: Double? = null,
    val result15m: String = "PENDING",
    val result30m: String = "PENDING",
    val result60m: String = "PENDING",
    val entryStatus: String = "BELUM DICEK",
    val evaluationTime15m: Long? = null,
    val evaluationTime30m: Long? = null,
    val evaluationTime60m: Long? = null,
    val priceSource15m: String? = null,
    val priceSource30m: String? = null,
    val priceSource60m: String? = null,
    val tradeOutcome: String = "PENDING",
    val tradeOutcomeTime: Long? = null
)

object SignalJournal {
    private const val PREFS = "btc_structure"
    private const val KEY = "signal_journal_v1"
    private const val MAX_RECORDS = 500

    fun read(context: Context): List<SignalRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                fun levels(key: String) = o.optJSONArray(key)?.let { a ->
                    (0 until a.length()).mapNotNull { a.optDouble(it).takeIf { n -> n.isFinite() && n > 0.0 } }
                } ?: emptyList()
                fun optionalPrice(key: String) = if (o.isNull(key) || !o.has(key)) null else o.optDouble(key).takeIf { it.isFinite() && it > 0.0 }
                SignalRecord(
                    id=o.optString("id"), time=o.optLong("time"), symbol=o.optString("symbol"),
                    direction=o.optString("direction"), price=o.optDouble("price"),
                    entryLow=o.optDouble("entryLow"), entryHigh=o.optDouble("entryHigh"),
                    stopLoss=o.optDouble("stopLoss"), takeProfit1=o.optDouble("takeProfit1"),
                    takeProfit2=o.optDouble("takeProfit2"), score=o.optInt("score"),
                    supports=levels("supports"), resistances=levels("resistances"),
                    price15m=optionalPrice("price15m"), price30m=optionalPrice("price30m"), price60m=optionalPrice("price60m"),
                    result15m=o.optString("result15m","PENDING"), result30m=o.optString("result30m","PENDING"),
                    result60m=o.optString("result60m","PENDING"),
                    entryStatus=o.optString("entryStatus","BELUM DICEK"),
                    evaluationTime15m=o.optLong("evaluationTime15m").takeIf { o.has("evaluationTime15m") && !o.isNull("evaluationTime15m") && it > 0L },
                    evaluationTime30m=o.optLong("evaluationTime30m").takeIf { o.has("evaluationTime30m") && !o.isNull("evaluationTime30m") && it > 0L },
                    evaluationTime60m=o.optLong("evaluationTime60m").takeIf { o.has("evaluationTime60m") && !o.isNull("evaluationTime60m") && it > 0L },
                    priceSource15m=o.optString("priceSource15m").takeIf { it.isNotBlank() },
                    priceSource30m=o.optString("priceSource30m").takeIf { it.isNotBlank() },
                    priceSource60m=o.optString("priceSource60m").takeIf { it.isNotBlank() },
                    tradeOutcome=o.optString("tradeOutcome", "PENDING"),
                    tradeOutcomeTime=o.optLong("tradeOutcomeTime").takeIf { o.has("tradeOutcomeTime") && !o.isNull("tradeOutcomeTime") && it > 0L }
                ).takeIf { it.symbol.isNotBlank() && it.price > 0.0 }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, records: List<SignalRecord>) {
        val array = JSONArray()
        records.take(MAX_RECORDS).forEach { r ->
            val o = JSONObject()
            o.put("id", r.id); o.put("time", r.time); o.put("symbol", r.symbol); o.put("direction", r.direction)
            o.put("price", r.price); o.put("entryLow", r.entryLow); o.put("entryHigh", r.entryHigh)
            o.put("stopLoss", r.stopLoss); o.put("takeProfit1", r.takeProfit1); o.put("takeProfit2", r.takeProfit2); o.put("score", r.score)
            o.put("supports", JSONArray(r.supports)); o.put("resistances", JSONArray(r.resistances))
            o.put("price15m", r.price15m ?: JSONObject.NULL); o.put("price30m", r.price30m ?: JSONObject.NULL); o.put("price60m", r.price60m ?: JSONObject.NULL)
            o.put("result15m", r.result15m); o.put("result30m", r.result30m); o.put("result60m", r.result60m); o.put("entryStatus", r.entryStatus)
            o.put("evaluationTime15m", r.evaluationTime15m ?: JSONObject.NULL); o.put("evaluationTime30m", r.evaluationTime30m ?: JSONObject.NULL); o.put("evaluationTime60m", r.evaluationTime60m ?: JSONObject.NULL)
            o.put("priceSource15m", r.priceSource15m ?: JSONObject.NULL); o.put("priceSource30m", r.priceSource30m ?: JSONObject.NULL); o.put("priceSource60m", r.priceSource60m ?: JSONObject.NULL)
            o.put("tradeOutcome", r.tradeOutcome); o.put("tradeOutcomeTime", r.tradeOutcomeTime ?: JSONObject.NULL)
            array.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    fun recordSignals(context: Context, coins: List<ScannedCoin>, now: Long = System.currentTimeMillis()) {
        var records = read(context)
        val latestBySymbol = records.filter { now - it.time in 0..15*60*1000L }.associateBy { it.symbol to it.direction }
        coins.forEach { coin ->
            val direction = if (coin.trend1h == Trend.BULLISH && coin.trend15m == Trend.BULLISH) "LONG" else "SHORT"
            if (latestBySymbol[coin.symbol to direction] != null) return@forEach
            val pad = coin.price * 0.00025
            val low = coin.price - pad
            val high = coin.price + pad
            val supports = coin.supportLevels
            val resistances = coin.resistanceLevels
            // Keep structural invalidation levels when available; use a conservative fallback otherwise.
            val stop = if (direction == "LONG") {
                (supports.firstOrNull() ?: coin.price * 0.996).let { if (it < low) it else coin.price * 0.996 }
            } else {
                (resistances.firstOrNull() ?: coin.price * 1.004).let { if (it > high) it else coin.price * 1.004 }
            }
            val risk = kotlin.math.abs(coin.price - stop).coerceAtLeast(coin.price * 0.001)
            val structuralTp1 = if (direction == "LONG") supportsAbove(coin, high) else supports.firstOrNull()
            val structuralTp2 = if (direction == "LONG") {
                coin.resistanceLevels.firstOrNull { it > (structuralTp1 ?: high) }
            } else {
                coin.supportLevels.firstOrNull { it < (structuralTp1 ?: low) }
            }
            // Targets must be on the profitable side and keep minimum 1.5R / 2R distances.
            val tp1 = if (direction == "LONG") {
                maxOf(structuralTp1?.takeIf { it > high } ?: coin.price * 1.005, coin.price + risk * 1.5)
            } else {
                minOf(structuralTp1?.takeIf { it < low } ?: coin.price * 0.995, coin.price - risk * 1.5)
            }
            val tp2 = if (direction == "LONG") {
                maxOf(structuralTp2?.takeIf { it > tp1 } ?: coin.price * 1.008, coin.price + risk * 2.0, tp1 * 1.001)
            } else {
                minOf(structuralTp2?.takeIf { it < tp1 } ?: coin.price * 0.992, coin.price - risk * 2.0, tp1 * 0.999)
            }
            records = listOf(SignalRecord(
                id="${coin.symbol}-${direction}-${now}", time=now, symbol=coin.symbol, direction=direction, price=coin.price,
                entryLow=low, entryHigh=high, stopLoss=stop, takeProfit1=tp1, takeProfit2=tp2,
                score=coin.score, supports=supports, resistances=resistances
            )) + records
        }
        write(context, records)
    }

    private fun supportsAbove(coin: ScannedCoin, above: Double): Double? =
        coin.resistanceLevels.firstOrNull { it > above }

    fun updatePrices(context: Context, prices: Map<String, Double>, now: Long = System.currentTimeMillis()) {
        if (prices.isEmpty()) return
        val updated = read(context).map { r ->
            val current = prices[r.symbol] ?: return@map r
            if (now - r.time >= 15 * 60 * 1000L || r.entryStatus == "TERLEWAT") return@map r
            when {
                current < r.entryLow || current > r.entryHigh -> r.copy(entryStatus = "TERLEWAT")
                r.entryStatus == "BELUM DICEK" -> r.copy(entryStatus = "MASIH DALAM RANGE")
                else -> r
            }
        }
        write(context, updated)
    }

    /** Returns due horizons that still need a historical Spot candle evaluation. */
    fun pendingEvaluations(context: Context, now: Long = System.currentTimeMillis()): List<PendingEvaluation> =
        read(context).flatMap { r ->
            listOf(15, 30, 60).mapNotNull { horizon ->
                val target = r.time + horizon * 60_000L
                val price = when (horizon) {
                    15 -> r.price15m
                    30 -> r.price30m
                    else -> r.price60m
                }
                if (price == null && now >= target) PendingEvaluation(r.id, r.symbol, horizon, target) else null
            }
        }.sortedBy { it.targetTime }

    /** Stores a historical candle close, not the live ticker at refresh time. */
    fun updateHistoricalEvaluation(
        context: Context,
        signalId: String,
        horizonMinutes: Int,
        price: Double,
        candleCloseTime: Long,
        source: String = "BINANCE_SPOT_1M_CANDLE_CLOSE"
    ) {
        if (price <= 0.0 || !price.isFinite() || horizonMinutes !in setOf(15, 30, 60)) return
        val updated = read(context).map { r ->
            if (r.id != signalId) return@map r
            when (horizonMinutes) {
                15 -> if (r.price15m == null) r.copy(price15m=price, result15m=classify(r, price), evaluationTime15m=candleCloseTime, priceSource15m=source) else r
                30 -> if (r.price30m == null) r.copy(price30m=price, result30m=classify(r, price), evaluationTime30m=candleCloseTime, priceSource30m=source) else r
                else -> if (r.price60m == null) r.copy(price60m=price, result60m=classify(r, price), evaluationTime60m=candleCloseTime, priceSource60m=source) else r
            }
        }
        write(context, updated)
    }

    /**
     * Evaluates hypothetical TP/SL touches from completed historical candles.
     * If TP and SL are both inside the same candle range, OHLC data cannot prove
     * which was touched first; mark AMBIGU rather than assuming a favorable order.
     */
    fun evaluateTradeOutcome(record: SignalRecord, bars: List<HistoricalBar>): TradeOutcome {
        if (record.entryStatus == "TERLEWAT") return TradeOutcome("ENTRY_TERLEWAT")
        if (record.entryStatus != "MASIH DALAM RANGE") return TradeOutcome("ENTRY_TIDAK_TERVERIFIKASI")
        val ordered = bars.filter {
            it.openTime >= record.time && it.closeTime <= record.time + 60 * 60_000L &&
                it.high.isFinite() && it.low.isFinite() && it.high > 0.0 && it.low > 0.0 && it.high >= it.low
        }.sortedBy { it.openTime }
        if (ordered.isEmpty()) return TradeOutcome("DATA_TIDAK_CUKUP")
        for (bar in ordered) {
            val slHit = if (record.direction == "LONG") bar.low <= record.stopLoss else bar.high >= record.stopLoss
            val tp2Hit = if (record.direction == "LONG") bar.high >= record.takeProfit2 else bar.low <= record.takeProfit2
            val tp1Hit = if (record.direction == "LONG") bar.high >= record.takeProfit1 else bar.low <= record.takeProfit1
            if (slHit && (tp1Hit || tp2Hit)) return TradeOutcome("AMBIGU", bar.closeTime)
            if (slHit) return TradeOutcome("SL_TERSENTUH", bar.closeTime)
            if (tp2Hit) return TradeOutcome("TP2_TERCAPAI", bar.closeTime)
            if (tp1Hit) return TradeOutcome("TP1_TERCAPAI", bar.closeTime)
        }
        return TradeOutcome("BELUM_TERCAPAI", ordered.last().closeTime)
    }

    fun pendingTradeOutcomes(context: Context, now: Long = System.currentTimeMillis()): List<SignalRecord> =
        read(context).filter { it.tradeOutcome == "PENDING" && now >= it.time + 60 * 60_000L }

    fun updateTradeOutcome(context: Context, signalId: String, outcome: TradeOutcome) {
        val updated = read(context).map { r ->
            if (r.id == signalId && r.tradeOutcome == "PENDING") r.copy(
                tradeOutcome = outcome.status,
                tradeOutcomeTime = outcome.eventTime
            ) else r
        }
        write(context, updated)
    }

    private fun classify(r: SignalRecord, current: Double): String {
        // This horizon label describes price direction only; it is not a TP/SL outcome.
        val move = if (r.direction == "LONG") current - r.price else r.price - current
        val pct = move / r.price * 100.0
        return when {
            pct > 0.05 -> "ARAH_POSITIF"
            pct < -0.05 -> "ARAH_NEGATIF"
            else -> "ARAH_NETRAL"
        }
    }

    fun fmt(price: Double): String = when {
        price >= 1000 -> "%,.2f".format(java.util.Locale.US, price)
        price >= 1 -> "%,.4f".format(java.util.Locale.US, price)
        price >= 0.01 -> "%,.6f".format(java.util.Locale.US, price)
        else -> "%,.8f".format(java.util.Locale.US, price)
    }
}
