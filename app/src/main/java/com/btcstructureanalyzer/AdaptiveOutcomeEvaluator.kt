package com.btcstructureanalyzer

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

data class AdaptiveOutcomeRecord(
    val id: String,
    val predictionId: String,
    val symbol: String,
    val decision: String,
    val horizonHours: Int,
    val targetTime: Long,
    val referencePrice: Double,
    val evaluatedPrice: Double,
    val evaluatedAt: Long,
    val signedReturnPercent: Double,
    val result: String
)

data class AdaptiveHorizonSummary(
    val horizonHours: Int,
    val eligible: Int,
    val evaluated: Int,
    val hits: Int,
    val misses: Int,
    val neutral: Int,
    val pending: Int
) {
    val coveragePercent: Double?
        get() = if (eligible > 0) evaluated * 100.0 / eligible else null
    val hitRatePercent: Double?
        get() = if (evaluated - neutral > 0) hits * 100.0 / (evaluated - neutral) else null
}

object AdaptiveOutcomeEvaluator {
    private const val PREFS = "btc_structure"
    private const val KEY = "adaptive_prediction_outcomes_v1"
    private const val BACKUP_KEY = "adaptive_prediction_outcomes_v1_backup"
    private const val LAST_ATTEMPT_KEY = "adaptive_outcome_last_attempt"
    private const val HOUR_MS = 3_600_000L
    private const val RETRY_INTERVAL_MS = 10 * 60_000L
    private const val MAX_REQUESTS_PER_PASS = 24
    private const val NEUTRAL_BAND_PERCENT = 0.05
    private val horizons = listOf(1, 3, 6)

    fun classify(decision: String, referencePrice: Double, evaluatedPrice: Double): Pair<Double, String> {
        require(decision == "LONG" || decision == "SHORT")
        require(referencePrice.isFinite() && referencePrice > 0.0)
        require(evaluatedPrice.isFinite() && evaluatedPrice > 0.0)
        val raw = (evaluatedPrice / referencePrice - 1.0) * 100.0
        val signed = if (decision == "LONG") raw else -raw
        val result = when {
            kotlin.math.abs(signed) <= NEUTRAL_BAND_PERCENT -> "NEUTRAL"
            signed > 0.0 -> "HIT"
            else -> "MISS"
        }
        return signed to result
    }

    fun read(context: Context): List<AdaptiveOutcomeRecord> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: prefs.getString(BACKUP_KEY, null) ?: return emptyList()
        return try {
            parse(raw)
        } catch (primary: Exception) {
            val backup = prefs.getString(BACKUP_KEY, null) ?: throw primary
            parse(backup)
        }
    }

    fun parse(raw: String): List<AdaptiveOutcomeRecord> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            AdaptiveOutcomeRecord(
                id = row.getString("id"),
                predictionId = row.getString("predictionId"),
                symbol = row.getString("symbol"),
                decision = row.getString("decision"),
                horizonHours = row.getInt("horizonHours"),
                targetTime = row.getLong("targetTime"),
                referencePrice = row.getDouble("referencePrice"),
                evaluatedPrice = row.getDouble("evaluatedPrice"),
                evaluatedAt = row.getLong("evaluatedAt"),
                signedReturnPercent = row.getDouble("signedReturnPercent"),
                result = row.getString("result")
            ).also {
                require(it.id.isNotBlank() && it.predictionId.isNotBlank() && it.symbol.isNotBlank())
                require(it.decision == "LONG" || it.decision == "SHORT")
                require(it.horizonHours in horizons)
                require(it.referencePrice.isFinite() && it.referencePrice > 0.0)
                require(it.evaluatedPrice.isFinite() && it.evaluatedPrice > 0.0)
                require(it.signedReturnPercent.isFinite())
                require(it.result in setOf("HIT", "MISS", "NEUTRAL"))
            }
        }
    }

    fun encode(records: List<AdaptiveOutcomeRecord>): String {
        val array = JSONArray()
        records.forEach { r ->
            array.put(JSONObject()
                .put("id", r.id)
                .put("predictionId", r.predictionId)
                .put("symbol", r.symbol)
                .put("decision", r.decision)
                .put("horizonHours", r.horizonHours)
                .put("targetTime", r.targetTime)
                .put("referencePrice", r.referencePrice)
                .put("evaluatedPrice", r.evaluatedPrice)
                .put("evaluatedAt", r.evaluatedAt)
                .put("signedReturnPercent", r.signedReturnPercent)
                .put("result", r.result))
        }
        return array.toString()
    }

    fun summarize(
        predictions: List<AdaptivePredictionRecord>,
        outcomes: List<AdaptiveOutcomeRecord>,
        now: Long
    ): List<AdaptiveHorizonSummary> = horizons.map { horizon ->
        val eligible = predictions.filter {
            (it.decision == "LONG" || it.decision == "SHORT") &&
                it.decisionTime + horizon * HOUR_MS <= now
        }
        val byId = outcomes.filter { it.horizonHours == horizon }.associateBy { it.predictionId }
        val completed = eligible.mapNotNull { byId[it.id] }
        AdaptiveHorizonSummary(
            horizonHours = horizon,
            eligible = eligible.size,
            evaluated = completed.size,
            hits = completed.count { it.result == "HIT" },
            misses = completed.count { it.result == "MISS" },
            neutral = completed.count { it.result == "NEUTRAL" },
            pending = (eligible.size - completed.size).coerceAtLeast(0)
        )
    }

    suspend fun evaluateDue(
        context: Context,
        repository: BinanceRepository,
        predictions: List<AdaptivePredictionRecord>,
        now: Long = System.currentTimeMillis()
    ): List<AdaptiveHorizonSummary> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastAttempt = prefs.getLong(LAST_ATTEMPT_KEY, 0L)
        if (now - lastAttempt < RETRY_INTERVAL_MS) return summarize(predictions, read(context), now)
        // Persist the throttle before network work so repeated dashboard refreshes cannot fan out requests.
        prefs.edit().putLong(LAST_ATTEMPT_KEY, now).commit()

        val existing = read(context)
        val known = existing.map { it.id }.toMutableSet()
        val due = predictions.asSequence()
            .filter { it.decision == "LONG" || it.decision == "SHORT" }
            .flatMap { prediction ->
                horizons.asSequence().map { horizon ->
                    Triple(prediction, horizon, prediction.decisionTime + horizon * HOUR_MS)
                }
            }
            .filter { (prediction, horizon, target) ->
                target <= now && "${prediction.id}-${horizon}h" !in known
            }
            .take(MAX_REQUESTS_PER_PASS)
            .toList()

        val semaphore = Semaphore(3)
        val completed = coroutineScope {
            due.map { (prediction, horizon, target) ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            repository.historicalSpotClose(prediction.symbol, target)?.let { (price, closeTime) ->
                                val (signed, result) = classify(prediction.decision, prediction.referencePrice, price)
                                AdaptiveOutcomeRecord(
                                    id = "${prediction.id}-${horizon}h",
                                    predictionId = prediction.id,
                                    symbol = prediction.symbol,
                                    decision = prediction.decision,
                                    horizonHours = horizon,
                                    targetTime = target,
                                    referencePrice = prediction.referencePrice,
                                    evaluatedPrice = price,
                                    evaluatedAt = closeTime,
                                    signedReturnPercent = signed,
                                    result = result
                                )
                            }
                        }.getOrNull()
                    }
                }
            }.mapNotNull { it.await() }
        }

        if (completed.isNotEmpty()) {
            val updated = (completed + existing).distinctBy { it.id }
            val encoded = encode(updated)
            check(prefs.edit().putString(KEY, encoded).putString(BACKUP_KEY, encoded).commit()) {
                "Gagal menyimpan hasil evaluasi Adaptive."
            }
        }
        return summarize(predictions, read(context), now)
    }
}
