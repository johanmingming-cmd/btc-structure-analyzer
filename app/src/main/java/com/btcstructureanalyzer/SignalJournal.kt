package com.btcstructureanalyzer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PendingEvaluation(val signalId: String, val symbol: String, val horizonMinutes: Int, val targetTime: Long)

/**
 * Optional legacy fields are retained only so records written by older APKs remain readable.
 * New signals do not calculate or populate entry/TP/SL values.
 */
data class SignalRecord(
    val id: String,
    val time: Long,
    val symbol: String,
    val direction: String,
    val price: Double,
    val score: Int,
    val supports: List<Double> = emptyList(),
    val resistances: List<Double> = emptyList(),
    val price10m: Double? = null,
    val price15m: Double? = null,
    val price30m: Double? = null,
    val price60m: Double? = null,
    val price180m: Double? = null,
    val price360m: Double? = null,
    val result10m: String = "PENDING",
    val result15m: String = "PENDING",
    val result30m: String = "PENDING",
    val result60m: String = "PENDING",
    val result180m: String = "PENDING",
    val result360m: String = "PENDING",
    val evaluationTime10m: Long? = null,
    val evaluationTime15m: Long? = null,
    val evaluationTime30m: Long? = null,
    val evaluationTime60m: Long? = null,
    val evaluationTime180m: Long? = null,
    val evaluationTime360m: Long? = null,
    val priceSource10m: String? = null,
    val priceSource15m: String? = null,
    val priceSource30m: String? = null,
    val priceSource60m: String? = null,
    val priceSource180m: String? = null,
    val priceSource360m: String? = null,
    val cohort: String = "LEGACY",
    val entryLow: Double? = null,
    val entryHigh: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit1: Double? = null,
    val takeProfit2: Double? = null,
    val entryStatus: String = "BELUM DICEK",
    val tradeOutcome: String = "PENDING",
    val tradeOutcomeTime: Long? = null
)

object SignalJournal {
    private const val PREFS = "btc_structure"
    private const val KEY = "signal_journal_v1"
    private const val BACKUP_KEY = "signal_journal_v1_backup"
    private const val CORRUPT_BACKUP_KEY = "signal_journal_v1_corrupt_backup"
    private const val RECOVERY_FLAG = "signal_journal_recovered_from_backup"
    private const val MAX_RECORDS = 500
    private val lock = Any()

    /** Strict read: malformed or partially corrupt journals are never converted to an empty list. */
    fun read(context: Context): List<SignalRecord> = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            if (prefs.contains(KEY)) {
                val primary = prefs.getString(KEY, null)
                    ?: throw IllegalStateException("Jurnal sinyal utama tidak memiliki nilai yang bisa dibaca.")
                try {
                    return@synchronized parseRecords(primary)
                } catch (primaryError: Exception) {
                    val backup = try { prefs.getString(BACKUP_KEY, null) } catch (_: ClassCastException) { null }
                        ?: throw IllegalStateException("Jurnal utama rusak dan cadangan tidak tersedia. Data asli tidak diubah.", primaryError)
                    val recovered = try { parseRecords(backup) } catch (backupError: Exception) {
                        throw IllegalStateException("Jurnal utama dan cadangan sama-sama tidak valid. Data asli tidak diubah.", backupError)
                    }
                    prefs.edit().putBoolean(RECOVERY_FLAG, true).commit()
                    return@synchronized recovered
                }
            }
            if (prefs.contains(BACKUP_KEY)) {
                val backup = prefs.getString(BACKUP_KEY, null)
                    ?: throw IllegalStateException("Cadangan jurnal sinyal tidak memiliki nilai yang bisa dibaca.")
                return@synchronized parseRecords(backup)
            }
            emptyList()
        } catch (error: ClassCastException) {
            throw IllegalStateException("Format penyimpanan jurnal tidak valid. Data lama tidak diubah.", error)
        }
    }

    /** Pure codec exposed for deterministic unit tests. */
    fun parseRecords(raw: String): List<SignalRecord> {
        try {
            val array = JSONArray(raw)
            return (0 until array.length()).map { index ->
                val row = array.optJSONObject(index)
                    ?: throw IllegalArgumentException("record[$index] bukan objek JSON")
                fun requiredString(key: String): String {
                    val value = row.opt(key)
                    require(value is String && value.isNotBlank()) { "field $key tidak valid" }
                    return value
                }
                fun requiredLong(key: String): Long {
                    val value = row.opt(key)
                    require(value is Number) { "field $key bukan angka" }
                    val number = value.toDouble()
                    require(number.isFinite() && number >= 0.0 && number % 1.0 == 0.0) { "field $key tidak valid" }
                    return number.toLong()
                }
                fun requiredInt(key: String): Int {
                    val value = row.opt(key)
                    require(value is Number) { "field $key bukan angka" }
                    val number = value.toDouble()
                    require(number.isFinite() && number % 1.0 == 0.0 && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE) {
                        "field $key tidak valid"
                    }
                    return number.toInt()
                }
                fun requiredPrice(key: String): Double {
                    val value = row.opt(key)
                    require(value is Number) { "field $key bukan angka" }
                    return value.toDouble().also { require(it.isFinite() && it > 0.0) { "field $key harus positif dan finite" } }
                }
                fun optionalPrice(key: String): Double? {
                    if (!row.has(key) || row.isNull(key)) return null
                    val value = row.opt(key)
                    require(value is Number) { "field $key bukan angka" }
                    return value.toDouble().also { require(it.isFinite() && it > 0.0) { "field $key harus positif dan finite" } }
                }
                // TP/SL and entry fields are legacy-only metadata; malformed values never block journal recovery.
                fun legacyPrice(key: String): Double? {
                    if (!row.has(key) || row.isNull(key)) return null
                    val value = row.opt(key) as? Number ?: return null
                    return value.toDouble().takeIf { it.isFinite() && it > 0.0 }
                }
                fun optionalTime(key: String): Long? {
                    if (!row.has(key) || row.isNull(key)) return null
                    val value = row.opt(key)
                    require(value is Number) { "field $key bukan angka" }
                    val number = value.toDouble()
                    require(number.isFinite() && number > 0.0 && number % 1.0 == 0.0) { "field $key tidak valid" }
                    return number.toLong()
                }
                fun legacyTime(key: String): Long? = runCatching { optionalTime(key) }.getOrNull()
                fun legacyString(key: String, default: String): String =
                    (row.opt(key) as? String)?.takeIf { it.isNotBlank() } ?: default
                fun optionalString(key: String, default: String? = null): String? {
                    if (!row.has(key) || row.isNull(key)) return default
                    return (row.opt(key) as? String)
                        ?: throw IllegalArgumentException("field $key harus berupa teks")
                }
                fun levels(key: String): List<Double> {
                    if (!row.has(key) || row.isNull(key)) return emptyList()
                    val values = row.optJSONArray(key) ?: throw IllegalArgumentException("field $key harus berupa array")
                    return (0 until values.length()).map { levelIndex ->
                        val value = values.opt(levelIndex)
                        require(value is Number) { "$key[$levelIndex] bukan angka" }
                        value.toDouble().also { require(it.isFinite() && it > 0.0) { "$key[$levelIndex] tidak valid" } }
                    }
                }

                val id = requiredString("id")
                val time = requiredLong("time")
                require(time > 0L) { "timestamp harus positif" }
                val symbol = requiredString("symbol")
                val direction = requiredString("direction")
                require(direction == "LONG" || direction == "SHORT") { "direction harus LONG atau SHORT" }
                val price = requiredPrice("price")
                val score = requiredInt("score")
                val entryLow = legacyPrice("entryLow")
                val entryHigh = legacyPrice("entryHigh")

                SignalRecord(
                    id = id, time = time, symbol = symbol, direction = direction, price = price, score = score,
                    supports = levels("supports"), resistances = levels("resistances"),
                    price10m = optionalPrice("price10m"), price15m = optionalPrice("price15m"),
                    price30m = optionalPrice("price30m"), price60m = optionalPrice("price60m"),
                    price180m = optionalPrice("price180m"), price360m = optionalPrice("price360m"),
                    result10m = optionalString("result10m", "PENDING")!!,
                    result15m = optionalString("result15m", "PENDING")!!,
                    result30m = optionalString("result30m", "PENDING")!!,
                    result60m = optionalString("result60m", "PENDING")!!,
                    result180m = optionalString("result180m", "PENDING")!!,
                    result360m = optionalString("result360m", "PENDING")!!,
                    evaluationTime10m = optionalTime("evaluationTime10m"),
                    evaluationTime15m = optionalTime("evaluationTime15m"),
                    evaluationTime30m = optionalTime("evaluationTime30m"),
                    evaluationTime60m = optionalTime("evaluationTime60m"),
                    evaluationTime180m = optionalTime("evaluationTime180m"),
                    evaluationTime360m = optionalTime("evaluationTime360m"),
                    priceSource10m = optionalString("priceSource10m"),
                    priceSource15m = optionalString("priceSource15m"),
                    priceSource30m = optionalString("priceSource30m"),
                    priceSource60m = optionalString("priceSource60m"),
                    priceSource180m = optionalString("priceSource180m"),
                    priceSource360m = optionalString("priceSource360m"),
                    cohort = optionalString("cohort", "LEGACY")!!,
                    entryLow = entryLow, entryHigh = entryHigh,
                    stopLoss = legacyPrice("stopLoss"), takeProfit1 = legacyPrice("takeProfit1"),
                    takeProfit2 = legacyPrice("takeProfit2"),
                    entryStatus = legacyString("entryStatus", "BELUM DICEK"),
                    tradeOutcome = legacyString("tradeOutcome", "PENDING"),
                    tradeOutcomeTime = legacyTime("tradeOutcomeTime")
                )
            }
        } catch (error: Exception) {
            if (error is IllegalStateException && error.message?.contains("existing data was left untouched") == true) throw error
            throw IllegalStateException("Jurnal sinyal tidak dapat dibaca; data yang ada dibiarkan utuh dan tidak ditimpa.", error)
        }
    }

    fun serializeRecords(records: List<SignalRecord>): String {
        val array = JSONArray()
        records.take(MAX_RECORDS).forEach { record ->
            val row = JSONObject()
                .put("id", record.id).put("time", record.time).put("symbol", record.symbol)
                .put("direction", record.direction).put("price", record.price).put("score", record.score)
                .put("supports", JSONArray(record.supports)).put("resistances", JSONArray(record.resistances))
                .put("price10m", record.price10m ?: JSONObject.NULL)
                .put("price15m", record.price15m ?: JSONObject.NULL)
                .put("price30m", record.price30m ?: JSONObject.NULL)
                .put("price60m", record.price60m ?: JSONObject.NULL)
                .put("price180m", record.price180m ?: JSONObject.NULL)
                .put("price360m", record.price360m ?: JSONObject.NULL)
                .put("result10m", record.result10m).put("result15m", record.result15m)
                .put("result30m", record.result30m).put("result60m", record.result60m)
                .put("result180m", record.result180m).put("result360m", record.result360m)
                .put("evaluationTime10m", record.evaluationTime10m ?: JSONObject.NULL)
                .put("evaluationTime15m", record.evaluationTime15m ?: JSONObject.NULL)
                .put("evaluationTime30m", record.evaluationTime30m ?: JSONObject.NULL)
                .put("evaluationTime60m", record.evaluationTime60m ?: JSONObject.NULL)
                .put("evaluationTime180m", record.evaluationTime180m ?: JSONObject.NULL)
                .put("evaluationTime360m", record.evaluationTime360m ?: JSONObject.NULL)
                .put("priceSource10m", record.priceSource10m ?: JSONObject.NULL)
                .put("priceSource15m", record.priceSource15m ?: JSONObject.NULL)
                .put("priceSource30m", record.priceSource30m ?: JSONObject.NULL)
                .put("priceSource60m", record.priceSource60m ?: JSONObject.NULL)
                .put("priceSource180m", record.priceSource180m ?: JSONObject.NULL)
                .put("priceSource360m", record.priceSource360m ?: JSONObject.NULL)
                .put("cohort", record.cohort)
            // Keep legacy fields only when they existed in the old record. New signals omit them.
            record.entryLow?.let { row.put("entryLow", it) }
            record.entryHigh?.let { row.put("entryHigh", it) }
            record.stopLoss?.let { row.put("stopLoss", it) }
            record.takeProfit1?.let { row.put("takeProfit1", it) }
            record.takeProfit2?.let { row.put("takeProfit2", it) }
            if (record.entryStatus != "BELUM DICEK") row.put("entryStatus", record.entryStatus)
            if (record.tradeOutcome != "PENDING") row.put("tradeOutcome", record.tradeOutcome)
            record.tradeOutcomeTime?.let { row.put("tradeOutcomeTime", it) }
            array.put(row)
        }
        return array.toString()
    }

    private fun writeLocked(context: Context, records: List<SignalRecord>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Refuse to replace a damaged primary journal. This preserves the original bytes for recovery.
        var damagedPrimary: String? = null
        if (prefs.contains(KEY)) {
            val current = try { prefs.getString(KEY, null) } catch (error: ClassCastException) {
                throw IllegalStateException("Jurnal utama bukan teks. Data yang ada tidak ditimpa.", error)
            } ?: throw IllegalStateException("Jurnal utama tidak dapat dibaca. Data yang ada tidak ditimpa.")
            try {
                parseRecords(current)
            } catch (primaryError: Exception) {
                val backup = try { prefs.getString(BACKUP_KEY, null) } catch (_: ClassCastException) { null }
                    ?: throw IllegalStateException("Jurnal utama rusak dan cadangan tidak tersedia. Data yang ada tidak ditimpa.", primaryError)
                parseRecords(backup)
                damagedPrimary = current
            }
        }
        val encoded = serializeRecords(records)
        val editor = prefs.edit().putString(KEY, encoded).putString(BACKUP_KEY, encoded)
            .putBoolean(RECOVERY_FLAG, false)
        damagedPrimary?.let { editor.putString(CORRUPT_BACKUP_KEY, it) }
        check(editor.commit()) {
            "Gagal menyimpan jurnal sinyal. Data sebelumnya tetap dipertahankan."
        }
    }

    fun recordSignals(context: Context, coins: List<ScannedCoin>, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (coins.isEmpty()) return@synchronized
        val records = read(context).toMutableList()
        val recentKeys = records.filter { now - it.time in 0..15 * 60 * 1000L }
            .map { it.symbol to it.direction }.toMutableSet()
        val additions = mutableListOf<SignalRecord>()
        coins.forEach { coin ->
            val direction = when {
                coin.trend1h == Trend.BULLISH && coin.trend15m == Trend.BULLISH -> "LONG"
                coin.trend1h == Trend.BEARISH && coin.trend15m == Trend.BEARISH -> "SHORT"
                else -> return@forEach
            }
            if (!recentKeys.add(coin.symbol to direction)) return@forEach
            additions += SignalRecord(
                id = "${coin.symbol}-${direction}-${now}", time = now, symbol = coin.symbol,
                direction = direction, price = coin.price, score = coin.score,
                supports = coin.supportLevels, resistances = coin.resistanceLevels, cohort = "PROSPECTIVE_V1"
            )
        }
        if (additions.isNotEmpty()) writeLocked(context, additions + records)
    }

    /** Only prospective forward-test signals are eligible; legacy history is display-only. */
    fun pendingEvaluations(context: Context, now: Long = System.currentTimeMillis()): List<PendingEvaluation> =
        pendingEvaluations(read(context), now)

    /** Pure queue builder exposed for deterministic unit tests. */
    fun pendingEvaluations(records: List<SignalRecord>, now: Long): List<PendingEvaluation> =
        records.asSequence()
            .filter { it.cohort == "PROSPECTIVE_V1" }
            .flatMap { record ->
                listOf(10, 15, 30, 60, 180, 360).mapNotNull { horizon ->
                    val target = record.time + horizon * 60_000L
                    val price = when (horizon) {
                        10 -> record.price10m
                        15 -> record.price15m
                        30 -> record.price30m
                        60 -> record.price60m
                        180 -> record.price180m
                        else -> record.price360m
                    }
                    val evaluatedAt = when (horizon) {
                        10 -> record.evaluationTime10m
                        15 -> record.evaluationTime15m
                        30 -> record.evaluationTime30m
                        60 -> record.evaluationTime60m
                        180 -> record.evaluationTime180m
                        else -> record.evaluationTime360m
                    }
                    val needsEvaluation = price == null || evaluatedAt == null ||
                        evaluatedAt > target || target - evaluatedAt > 60_000L
                    if (needsEvaluation && now >= target) PendingEvaluation(record.id, record.symbol, horizon, target) else null
                }.asSequence()
            }
            .sortedBy { it.targetTime }
            .toList()

    /** Stores only a completed historical Spot candle close at or immediately before its target. */
    fun updateHistoricalEvaluation(
        context: Context,
        signalId: String,
        horizonMinutes: Int,
        price: Double,
        candleCloseTime: Long,
        source: String = "BINANCE_SPOT_1M_CANDLE_CLOSE"
    ): Boolean = synchronized(lock) {
        if (price <= 0.0 || !price.isFinite() || horizonMinutes !in setOf(10, 15, 30, 60, 180, 360)) return@synchronized false
        val records = read(context)
        val updated = records.map { record ->
            if (record.id != signalId) return@map record
            val target = record.time + horizonMinutes * 60_000L
            if (candleCloseTime > target || candleCloseTime < target - 60_000L) return@map record
            val oldTime = when (horizonMinutes) {
                10 -> record.evaluationTime10m
                15 -> record.evaluationTime15m
                30 -> record.evaluationTime30m
                60 -> record.evaluationTime60m
                180 -> record.evaluationTime180m
                else -> record.evaluationTime360m
            }
            val oldPrice = when (horizonMinutes) {
                10 -> record.price10m
                15 -> record.price15m
                30 -> record.price30m
                60 -> record.price60m
                180 -> record.price180m
                else -> record.price360m
            }
            if (oldPrice != null && oldTime != null && oldTime <= target && target - oldTime <= 60_000L) return@map record
            val favorablePct = (if (record.direction == "LONG") price - record.price else record.price - price) / record.price * 100.0
            val result = when {
                favorablePct > 0.05 -> "ARAH_POSITIF"
                favorablePct < -0.05 -> "ARAH_NEGATIF"
                else -> "ARAH_NETRAL"
            }
            when (horizonMinutes) {
                10 -> record.copy(price10m = price, result10m = result, evaluationTime10m = candleCloseTime, priceSource10m = source)
                15 -> record.copy(price15m = price, result15m = result, evaluationTime15m = candleCloseTime, priceSource15m = source)
                30 -> record.copy(price30m = price, result30m = result, evaluationTime30m = candleCloseTime, priceSource30m = source)
                60 -> record.copy(price60m = price, result60m = result, evaluationTime60m = candleCloseTime, priceSource60m = source)
                180 -> record.copy(price180m = price, result180m = result, evaluationTime180m = candleCloseTime, priceSource180m = source)
                else -> record.copy(price360m = price, result360m = result, evaluationTime360m = candleCloseTime, priceSource360m = source)
            }
        }
        if (updated == records) return@synchronized false
        writeLocked(context, updated)
        true
    }

    fun fmt(price: Double): String = when {
        price >= 1000 -> "%,.2f".format(java.util.Locale.US, price)
        price >= 1 -> "%,.4f".format(java.util.Locale.US, price)
        price >= 0.01 -> "%,.6f".format(java.util.Locale.US, price)
        else -> "%,.8f".format(java.util.Locale.US, price)
    }
}
