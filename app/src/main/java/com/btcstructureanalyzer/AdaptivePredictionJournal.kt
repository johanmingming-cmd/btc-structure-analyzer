package com.btcstructureanalyzer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AdaptivePredictionRecord(
    val id: String,
    val symbol: String,
    val decisionTime: Long,
    val referencePrice: Double,
    val legacyDirection: String,
    val decision: String,
    val regime: String,
    val reason: String,
    val atrPercent: Double?,
    val volatilityExpansion: Double?,
    val efficiencyRatio: Double?,
    val normalizedSlope: Double?,
    val explanation: String,
    val setupFamily: String,
    val qualityScore: Int,
    val engineVersion: String
) {
    companion object {
        fun fromShadow(shadow: AdaptiveShadowRecord) = AdaptivePredictionRecord(
            id = "${shadow.symbol}-${shadow.decisionTime}",
            symbol = shadow.symbol,
            decisionTime = shadow.decisionTime,
            referencePrice = shadow.referencePrice,
            legacyDirection = shadow.legacyDirection.name,
            decision = shadow.adaptive.decision.name,
            regime = shadow.adaptive.regime.name,
            reason = shadow.adaptive.reason.name,
            atrPercent = shadow.adaptive.atrPercent,
            volatilityExpansion = shadow.adaptive.volatilityExpansion,
            efficiencyRatio = shadow.adaptive.efficiencyRatio,
            normalizedSlope = shadow.adaptive.normalizedSlope,
            explanation = shadow.adaptive.explanation,
            setupFamily = shadow.adaptive.setupFamily.name,
            qualityScore = shadow.adaptive.qualityScore,
            engineVersion = shadow.engineVersion
        )
    }
}

/** Persistent, append-only local journal for Adaptive Prediction shadow decisions. */
object AdaptivePredictionJournal {
    private const val PREFS = "btc_structure"
    private const val KEY = "adaptive_prediction_journal_v1"
    private const val BACKUP_KEY = "adaptive_prediction_journal_v1_backup"
    private const val MAX_RECORDS = 10_000
    private val lock = Any()

    fun read(context: Context): List<AdaptivePredictionRecord> = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: prefs.getString(BACKUP_KEY, null) ?: return@synchronized emptyList()
        try {
            parseRecords(raw)
        } catch (primaryError: Exception) {
            val backup = prefs.getString(BACKUP_KEY, null)
                ?: throw IllegalStateException("Jurnal Adaptive rusak dan cadangan tidak tersedia.", primaryError)
            try {
                parseRecords(backup)
            } catch (backupError: Exception) {
                throw IllegalStateException("Jurnal Adaptive utama dan cadangan tidak valid.", backupError)
            }
        }
    }

    fun record(context: Context, shadows: List<AdaptiveShadowRecord>): Int = synchronized(lock) {
        if (shadows.isEmpty()) return@synchronized 0
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = read(context)
        val keys = existing.map { it.id }.toMutableSet()
        val additions = shadows.map(AdaptivePredictionRecord::fromShadow).filter { keys.add(it.id) }
        if (additions.isEmpty()) return@synchronized 0
        val updated = (additions + existing).take(MAX_RECORDS)
        val encoded = encodeRecords(updated)
        check(prefs.edit().putString(KEY, encoded).putString(BACKUP_KEY, encoded).commit()) {
            "Gagal menyimpan jurnal Adaptive Prediction."
        }
        additions.size
    }

    fun parseRecords(raw: String): List<AdaptivePredictionRecord> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            val row = array.optJSONObject(index)
                ?: throw IllegalArgumentException("adaptive[$index] bukan objek JSON")
            AdaptivePredictionRecord(
                id = row.getString("id"),
                symbol = row.getString("symbol"),
                decisionTime = row.getLong("decisionTime"),
                referencePrice = row.getDouble("referencePrice"),
                legacyDirection = row.getString("legacyDirection"),
                decision = row.getString("decision"),
                regime = row.getString("regime"),
                reason = row.getString("reason"),
                atrPercent = row.optNullableDouble("atrPercent"),
                volatilityExpansion = row.optNullableDouble("volatilityExpansion"),
                efficiencyRatio = row.optNullableDouble("efficiencyRatio"),
                normalizedSlope = row.optNullableDouble("normalizedSlope"),
                explanation = row.getString("explanation"),
                setupFamily = row.getString("setupFamily"),
                qualityScore = row.getInt("qualityScore"),
                engineVersion = row.getString("engineVersion")
            ).also {
                require(it.id.isNotBlank() && it.symbol.isNotBlank())
                require(it.decisionTime > 0L && it.referencePrice.isFinite() && it.referencePrice > 0.0)
            }
        }
    }

    fun encodeRecords(records: List<AdaptivePredictionRecord>): String {
        val array = JSONArray()
        records.forEach { r ->
            array.put(JSONObject()
                .put("id", r.id)
                .put("symbol", r.symbol)
                .put("decisionTime", r.decisionTime)
                .put("referencePrice", r.referencePrice)
                .put("legacyDirection", r.legacyDirection)
                .put("decision", r.decision)
                .put("regime", r.regime)
                .put("reason", r.reason)
                .put("atrPercent", r.atrPercent ?: JSONObject.NULL)
                .put("volatilityExpansion", r.volatilityExpansion ?: JSONObject.NULL)
                .put("efficiencyRatio", r.efficiencyRatio ?: JSONObject.NULL)
                .put("normalizedSlope", r.normalizedSlope ?: JSONObject.NULL)
                .put("explanation", r.explanation)
                .put("setupFamily", r.setupFamily)
                .put("qualityScore", r.qualityScore)
                .put("engineVersion", r.engineVersion))
        }
        return array.toString()
    }

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() }

    fun csv(records: List<AdaptivePredictionRecord>): String = buildString {
        appendLine("record_id,decision_time_utc,symbol,reference_price,adaptive_decision,regime,reason,legacy_direction,atr_percent,volatility_expansion,efficiency_ratio,normalized_slope,setup_family,quality_score,engine_version,explanation")
        records.forEach { r ->
            appendLine(listOf(
                csvField(r.id), csvField(java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(r.decisionTime))),
                csvField(r.symbol), r.referencePrice.toString(), csvField(r.decision), csvField(r.regime),
                csvField(r.reason), csvField(r.legacyDirection), r.atrPercent?.toString() ?: "",
                r.volatilityExpansion?.toString() ?: "", r.efficiencyRatio?.toString() ?: "",
                r.normalizedSlope?.toString() ?: "", csvField(r.setupFamily), r.qualityScore.toString(),
                csvField(r.engineVersion), csvField(r.explanation)
            ).joinToString(","))
        }
    }

    private fun csvField(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
}
