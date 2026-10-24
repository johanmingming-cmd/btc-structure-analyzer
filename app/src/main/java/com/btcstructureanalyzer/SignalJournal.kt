package com.btcstructureanalyzer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

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
    val result60m: String = "PENDING"
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
                    result60m=o.optString("result60m","PENDING")
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
            o.put("result15m", r.result15m); o.put("result30m", r.result30m); o.put("result60m", r.result60m)
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
            val stop = if (direction == "LONG") {
                (supports.firstOrNull() ?: coin.price * 0.996).let { if (it < low) it else coin.price * 0.996 }
            } else {
                (resistances.firstOrNull() ?: coin.price * 1.004).let { if (it > high) it else coin.price * 1.004 }
            }
            val tp1 = if (direction == "LONG") supportsAbove(coin, high) ?: coin.price * 1.005 else supports.firstOrNull() ?: coin.price * 0.995
            val tp2 = if (direction == "LONG") (coin.price * 1.008).coerceAtLeast(tp1 * 1.001) else (coin.price * 0.992).coerceAtMost(tp1 * 0.999)
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
            var next = r
            val elapsed = now - r.time
            if (elapsed >= 15*60*1000L && r.price15m == null) next = next.copy(price15m=current, result15m=classify(r, current))
            if (elapsed >= 30*60*1000L && r.price30m == null) next = next.copy(price30m=current, result30m=classify(r, current))
            if (elapsed >= 60*60*1000L && r.price60m == null) next = next.copy(price60m=current, result60m=classify(r, current))
            next
        }
        write(context, updated)
    }

    private fun classify(r: SignalRecord, current: Double): String {
        val move = if (r.direction == "LONG") current - r.price else r.price - current
        val pct = move / r.price * 100.0
        return when { pct > 0.05 -> "BENAR"; pct < -0.05 -> "SALAH"; else -> "NETRAL" }
    }

    fun fmt(price: Double): String = when {
        price >= 1000 -> "%,.2f".format(java.util.Locale.US, price)
        price >= 1 -> "%,.4f".format(java.util.Locale.US, price)
        price >= 0.01 -> "%,.6f".format(java.util.Locale.US, price)
        else -> "%,.8f".format(java.util.Locale.US, price)
    }
}
