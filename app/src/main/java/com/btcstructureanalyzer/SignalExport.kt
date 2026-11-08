package com.btcstructureanalyzer

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Read-only export of the existing local signal journal. */
object SignalExport {
    private data class Horizon(val minutes: Int, val price: Double?, val result: String, val evaluationTime: Long?, val priceSource: String?)

    fun writeZip(
        output: OutputStream,
        records: List<SignalRecord>,
        adaptiveRecords: List<AdaptivePredictionRecord> = emptyList(),
        adaptiveOutcomes: List<AdaptiveOutcomeRecord> = emptyList()
    ) {
        ZipOutputStream(output.buffered()).use { zip ->
            val exportedAt = System.currentTimeMillis()
            putText(zip, "signals.csv", signalsCsv(records))
            putText(zip, "evaluations.csv", evaluationsCsv(records))
            putText(zip, "signal-analysis.json", analysisJson(records, exportedAt).toString(2))
            putText(zip, "adaptive-predictions.csv", AdaptivePredictionJournal.csv(adaptiveRecords))
            putText(zip, "adaptive-predictions.json", adaptiveJson(adaptiveRecords, exportedAt).toString(2))
            putText(zip, "adaptive-outcomes.csv", adaptiveOutcomesCsv(adaptiveRecords, adaptiveOutcomes, exportedAt))
            putText(zip, "adaptive-outcomes.json", adaptiveOutcomesJson(adaptiveRecords, adaptiveOutcomes, exportedAt).toString(2))
            putText(zip, "README.txt", readme(records.size, adaptiveRecords.size, adaptiveOutcomes.size, exportedAt))
            zip.finish()
        }
    }

    private fun horizons(r: SignalRecord) = listOf(
        Horizon(10, r.price10m, r.result10m, r.evaluationTime10m, r.priceSource10m),
        Horizon(15, r.price15m, r.result15m, r.evaluationTime15m, r.priceSource15m),
        Horizon(30, r.price30m, r.result30m, r.evaluationTime30m, r.priceSource30m),
        Horizon(60, r.price60m, r.result60m, r.evaluationTime60m, r.priceSource60m),
        Horizon(180, r.price180m, r.result180m, r.evaluationTime180m, r.priceSource180m),
        Horizon(360, r.price360m, r.result360m, r.evaluationTime360m, r.priceSource360m)
    )

    /** Quality is cohort-aware so legacy records cannot be mistaken for prospective samples. */
    private fun dataQuality(r: SignalRecord, h: Horizon): String = when {
        !r.cohort.equals("PROSPECTIVE_V1", ignoreCase = true) -> "LEGACY_COHORT_NOT_PROSPECTIVE"
        h.price == null -> "EVALUATION_PRICE_MISSING"
        h.evaluationTime == null -> "PRICE_PRESENT_EVALUATION_TIME_NOT_STORED"
        h.priceSource.isNullOrBlank() -> "PRICE_PRESENT_SOURCE_NOT_STORED"
        h.priceSource != "BINANCE_SPOT_1M_CANDLE_CLOSE" -> "LEGACY_NON_HISTORICAL_EVALUATION"
        kotlin.math.abs(h.evaluationTime - (r.time + h.minutes * 60_000L)) > 90_000L -> "EVALUATION_OUTSIDE_TARGET_WINDOW"
        else -> "HISTORICAL_CANDLE_WITHIN_TARGET_WINDOW"
    }

    private fun signalsCsv(records: List<SignalRecord>): String = buildString {
        appendLine("signal_id,cohort,signal_time_utc,symbol,direction,signal_price,score,candidate_ranking_score,supports,resistances,result_10m,result_15m,result_30m,result_60m,result_180m,result_360m")
        records.forEach { r ->
            appendLine(listOf(
                csv(r.id), csv(r.cohort), csv(isoUtc(r.time)), csv(r.symbol), csv(r.direction), num(r.price), r.score.toString(), r.score.toString(),
                csv(r.supports.joinToString("|") { it.toString() }),
                csv(r.resistances.joinToString("|") { it.toString() }),
                csv(r.result10m), csv(r.result15m), csv(r.result30m), csv(r.result60m), csv(r.result180m), csv(r.result360m)
            ).joinToString(","))
        }
    }

    private fun evaluationsCsv(records: List<SignalRecord>): String = buildString {
        appendLine("signal_id,cohort,symbol,direction,target_horizon_minutes,signal_time_utc,target_time_utc,evaluation_time_utc,signal_price,evaluation_price,price_change,evaluation_price_change_pct,direction_favorable_change_pct,raw_result,direction_correct,price_source,delay_seconds,data_quality")
        records.forEach { r ->
            horizons(r).forEach { h ->
                val delta = h.price?.minus(r.price)
                val pct = if (h.price != null && r.price > 0.0) ((h.price - r.price) / r.price) * 100.0 else null
                val favorable = pct?.let { if (r.direction.equals("LONG", true)) it else -it }
                val directionCorrect = favorable?.let { when { it > 0.05 -> "true"; it < -0.05 -> "false"; else -> "null" } } ?: "null"
                val quality = dataQuality(r, h)
                appendLine(listOf(
                    csv(r.id), csv(r.cohort), csv(r.symbol), csv(r.direction), h.minutes.toString(),
                    csv(isoUtc(r.time)), csv(isoUtc(r.time + h.minutes * 60_000L)), h.evaluationTime?.let { csv(isoUtc(it)) } ?: "",
                    num(r.price), h.price?.let(::num) ?: "", delta?.let(::num) ?: "", pct?.let(::num) ?: "",
                    favorable?.let(::num) ?: "", csv(h.result), directionCorrect, csv(h.priceSource ?: "UNKNOWN_NOT_STORED"),
                    h.evaluationTime?.let { ((it - (r.time + h.minutes * 60_000L)) / 1000L).toString() } ?: "", csv(quality)
                ).joinToString(","))
            }
        }
    }

    private fun analysisJson(records: List<SignalRecord>, exportedAt: Long): JSONObject {
        val signals = JSONArray()
        records.forEach { r ->
            val evaluations = JSONArray()
            horizons(r).forEach { h ->
                val delta = h.price?.minus(r.price)
                val pct = if (h.price != null && r.price > 0.0) ((h.price - r.price) / r.price) * 100.0 else null
                val favorable = pct?.let { if (r.direction.equals("LONG", true)) it else -it }
                val evaluation = JSONObject()
                    .put("target_horizon_minutes", h.minutes)
                    .put("target_time_utc", isoUtc(r.time + h.minutes * 60_000L))
                    .put("evaluation_time_utc", h.evaluationTime?.let { isoUtc(it) } ?: JSONObject.NULL)
                    .put("evaluation_price", h.price ?: JSONObject.NULL)
                    .put("price_change", delta ?: JSONObject.NULL)
                    .put("evaluation_price_change_pct", pct ?: JSONObject.NULL)
                    .put("direction_favorable_change_pct", favorable ?: JSONObject.NULL)
                    .put("raw_result", h.result)
                    .put("direction_correct", when {
                        favorable == null -> JSONObject.NULL
                        favorable > 0.05 -> true
                        favorable < -0.05 -> false
                        else -> JSONObject.NULL
                    })
                    .put("price_source", h.priceSource ?: "UNKNOWN_NOT_STORED")
                    .put("delay_seconds", h.evaluationTime?.let { (it - (r.time + h.minutes * 60_000L)) / 1000L } ?: JSONObject.NULL)
                    .put("data_quality", dataQuality(r, h))
                evaluations.put(evaluation)
            }
            signals.put(JSONObject()
                .put("signal_id", r.id)
                .put("cohort", r.cohort)
                .put("signal_time_epoch_ms", r.time)
                .put("signal_time_utc", isoUtc(r.time))
                .put("symbol", r.symbol)
                .put("direction", r.direction)
                .put("signal_price", r.price)
                .put("score", r.score)
                .put("candidate_ranking_score", r.score)
                .put("score_semantics", "CANDIDATE_RANKING_ONLY_NOT_DIRECTIONAL_CONFIDENCE")
                .put("supports", JSONArray(r.supports))
                .put("resistances", JSONArray(r.resistances))
                .put("evaluations", evaluations))
        }
        return JSONObject()
            .put("schema_version", "1.0")
            .put("export_time_utc", isoUtc(exportedAt))
            .put("export_time_epoch_ms", exportedAt)
            .put("timezone", "UTC")
            .put("record_count", records.size)
            .put("export_mode", "READ_ONLY_LOCAL_SIGNAL_JOURNAL")
            .put("limitations", JSONArray(listOf(
                "Legacy cohort records are marked LEGACY_COHORT_NOT_PROSPECTIVE and must be excluded from prospective accuracy summaries.",
                "Legacy records may not store the exact evaluation timestamp or price source.",
                "New evaluations use the nearest completed Binance Spot 1m candle close around the target time. The stored evaluation timestamp is the candle close timestamp; quality is marked outside the target window when it differs by more than 90 seconds.",
                "target_time_utc is calculated from signal time and horizon; it is not the actual evaluation time.",
                "Direction correctness is derived from stored price and the existing 0.05 percent threshold; it is not evidence of a profitable trade.",
                "No historical OHLC candles are included, so TP/SL hit order cannot be determined."
            )))
            .put("signals", signals)
    }

    private fun adaptiveJson(records: List<AdaptivePredictionRecord>, exportedAt: Long): JSONObject {
        val rows = JSONArray()
        records.forEach { r ->
            rows.put(JSONObject()
                .put("record_id", r.id)
                .put("decision_time_utc", isoUtc(r.decisionTime))
                .put("decision_time_epoch_ms", r.decisionTime)
                .put("symbol", r.symbol)
                .put("reference_price", r.referencePrice)
                .put("adaptive_decision", r.decision)
                .put("regime", r.regime)
                .put("reason", r.reason)
                .put("legacy_direction", r.legacyDirection)
                .put("atr_percent", r.atrPercent ?: JSONObject.NULL)
                .put("volatility_expansion", r.volatilityExpansion ?: JSONObject.NULL)
                .put("efficiency_ratio", r.efficiencyRatio ?: JSONObject.NULL)
                .put("normalized_slope", r.normalizedSlope ?: JSONObject.NULL)
                .put("setup_family", r.setupFamily)
                .put("quality_score", r.qualityScore)
                .put("engine_version", r.engineVersion)
                .put("explanation", r.explanation))
        }
        return JSONObject()
            .put("schema_version", "1.0")
            .put("export_time_utc", isoUtc(exportedAt))
            .put("timezone", "UTC")
            .put("record_count", records.size)
            .put("source", "LOCAL_ADAPTIVE_PREDICTION_SHADOW_JOURNAL")
            .put("limitations", JSONArray(listOf(
                "These are shadow predictions and do not control published scanner signals.",
                "Reference prices are the Binance Spot ticker prices captured during scan, not guaranteed executable prices.",
                "Outcome evaluation is intentionally deferred until the collected live dataset is reviewed."
            )))
            .put("predictions", rows)
    }

    internal fun adaptiveOutcomesCsv(
        predictions: List<AdaptivePredictionRecord>,
        outcomes: List<AdaptiveOutcomeRecord>,
        exportedAt: Long
    ): String = buildString {
        appendLine("prediction_id,symbol,decision,decision_time_utc,horizon_hours,target_time_utc,status,reference_price,evaluated_price,signed_return_percent,result,evaluated_at_utc")
        val byKey = outcomes.associateBy { "${it.predictionId}-${it.horizonHours}h" }
        predictions.filter { it.decision == "LONG" || it.decision == "SHORT" }.forEach { prediction ->
            listOf(1, 3, 6).forEach { horizon ->
                val target = prediction.decisionTime + horizon * 3_600_000L
                val outcome = byKey["${prediction.id}-${horizon}h"]
                val status = when {
                    outcome != null -> outcome.result
                    target > exportedAt -> "NOT_DUE"
                    else -> "PENDING"
                }
                appendLine(listOf(
                    csv(prediction.id), csv(prediction.symbol), csv(prediction.decision),
                    csv(isoUtc(prediction.decisionTime)), horizon.toString(), csv(isoUtc(target)),
                    csv(status), prediction.referencePrice.toString(),
                    outcome?.evaluatedPrice?.let(::num) ?: "",
                    outcome?.signedReturnPercent?.let(::num) ?: "",
                    csv(outcome?.result ?: status),
                    outcome?.evaluatedAt?.let(::isoUtc)?.let(::csv) ?: ""
                ).joinToString(","))
            }
        }
    }

    internal fun adaptiveOutcomesJson(
        predictions: List<AdaptivePredictionRecord>,
        outcomes: List<AdaptiveOutcomeRecord>,
        exportedAt: Long
    ): JSONObject {
        val byKey = outcomes.associateBy { "${it.predictionId}-${it.horizonHours}h" }
        val rows = JSONArray()
        predictions.filter { it.decision == "LONG" || it.decision == "SHORT" }.forEach { prediction ->
            listOf(1, 3, 6).forEach { horizon ->
                val target = prediction.decisionTime + horizon * 3_600_000L
                val outcome = byKey["${prediction.id}-${horizon}h"]
                val status = when {
                    outcome != null -> outcome.result
                    target > exportedAt -> "NOT_DUE"
                    else -> "PENDING"
                }
                rows.put(JSONObject()
                    .put("prediction_id", prediction.id)
                    .put("symbol", prediction.symbol)
                    .put("decision", prediction.decision)
                    .put("decision_time_utc", isoUtc(prediction.decisionTime))
                    .put("horizon_hours", horizon)
                    .put("target_time_utc", isoUtc(target))
                    .put("status", status)
                    .put("reference_price", prediction.referencePrice)
                    .put("evaluated_price", outcome?.evaluatedPrice ?: JSONObject.NULL)
                    .put("signed_return_percent", outcome?.signedReturnPercent ?: JSONObject.NULL)
                    .put("result", outcome?.result ?: status)
                    .put("evaluated_at_utc", outcome?.evaluatedAt?.let(::isoUtc) ?: JSONObject.NULL))
            }
        }
        val summaries = JSONArray()
        AdaptiveOutcomeEvaluator.summarize(predictions, outcomes, exportedAt).forEach { summary ->
            summaries.put(JSONObject()
                .put("horizon_hours", summary.horizonHours)
                .put("eligible", summary.eligible)
                .put("evaluated", summary.evaluated)
                .put("hits", summary.hits)
                .put("misses", summary.misses)
                .put("neutral", summary.neutral)
                .put("pending", summary.pending)
                .put("coverage_percent", summary.coveragePercent ?: JSONObject.NULL)
                .put("hit_rate_percent_excluding_neutral", summary.hitRatePercent ?: JSONObject.NULL))
        }
        return JSONObject()
            .put("schema_version", "1.0")
            .put("export_time_utc", isoUtc(exportedAt))
            .put("timezone", "UTC")
            .put("prediction_count", predictions.count { it.decision == "LONG" || it.decision == "SHORT" })
            .put("outcome_record_count", outcomes.size)
            .put("summaries", summaries)
            .put("outcomes", rows)
            .put("limitations", JSONArray(listOf(
                "Only Adaptive LONG/SHORT predictions are eligible; WAIT decisions are excluded.",
                "NOT_DUE means the target horizon had not elapsed at export time; PENDING means it elapsed but no outcome was stored.",
                "HIT/MISS/NEUTRAL are directional price-movement classifications using the existing 0.05 percent neutral band, not trade profitability.",
                "No fees, slippage, funding, or TP/SL path simulation is included."
            )))
    }

    private fun readme(count: Int, adaptiveCount: Int, adaptiveOutcomeCount: Int, exportedAt: Long) = """
BTC STRUCTURE ANALYZER - EXPORT DATA
Schema version: 1.0
Export time (UTC): ${isoUtc(exportedAt)}
Exported signal count: $count
Exported Adaptive Prediction decision count: $adaptiveCount
Exported Adaptive outcome record count: $adaptiveOutcomeCount
Cohort metadata: LEGACY records predate prospective cohort tracking; PROSPECTIVE_V1 records were captured after prospective tracking was added. Filter by cohort for new-sample analysis.

FILES
- signals.csv: one row per stored signal.
- evaluations.csv: one row per signal and horizon (10, 15, 30, 60 minutes, 3 hours, 6 hours).
- signal-analysis.json: structured archive including limitations and derived fields.
- adaptive-predictions.csv: one row per saved Adaptive Prediction decision, including WAIT decisions.
- adaptive-predictions.json: structured Adaptive Prediction archive with regime metrics and explanations.
- adaptive-outcomes.csv: one row per LONG/SHORT prediction and 1h/3h/6h horizon; includes NOT_DUE and PENDING statuses.
- adaptive-outcomes.json: structured outcome rows plus horizon summaries (eligible, evaluated, hits, misses, neutral, pending, coverage, hit rate).
- README.txt: this documentation.

DATA SAFETY
This export reads the local signal journal and Adaptive Prediction journal; it does not write to or delete either journal.
The ZIP contains no API keys or credentials.
Adaptive outcome rows are read from the local Adaptive outcome journal and are not recalculated during export.
Adaptive predictions are deduplicated by symbol and completed-hour decision timestamp. The local journal retains up to 10,000 records.

TIME FIELDS
signal_time_utc is the stored signal creation time.
target_time_utc is calculated as signal time + 10/15/30/60 minutes.
evaluation_time_utc is the timestamp of the historical 1m candle close used for evaluation.
Legacy records may lack evaluation timestamps or source metadata; new records store both.
data_quality first excludes LEGACY records from prospective analysis, then identifies missing metadata and evaluations outside the +/-90 second target window.
All timestamps included here are UTC.

EVALUATION FIELDS
price_change = evaluation price - signal price.
evaluation_price_change_pct = (evaluation price - signal price) / signal price * 100.
direction_favorable_change_pct is positive when movement favors the stored LONG/SHORT direction.
direction_correct is true above +0.05%, false below -0.05%, and null within the threshold
or when evaluation price is missing. This derived field is not a trade-profit result.
raw_result preserves the original journal result (e.g. BENAR, SALAH, NETRAL, PENDING).
Missing prices are blank in CSV and null in JSON; they are never converted to zero.

ADAPTIVE OUTCOME FIELDS
HIT/MISS/NEUTRAL classify directional price movement using the existing 0.05% neutral band.
PENDING means the horizon has elapsed but no result is stored; NOT_DUE means the horizon is still in the future.
Coverage = evaluated / eligible. Hit rate excludes NEUTRAL results and is not profitability.

LIMITATIONS
The current journal holds up to 500 records. Existing evaluation prices may have been fetched
during a manual refresh after the target horizon, rather than at the exact target time.
Legacy records may lack exact evaluation timestamps and source metadata. Evaluated prices and candle close times are stored, but the full historical OHLC series needed to determine TP/SL hit order is not stored.
This export cannot establish whether entry was reached or whether TP/SL was hit first.
PENDING or missing evaluations are not counted as losses.

CSV FORMAT
UTF-8, comma-separated. Text fields are quoted and embedded quotes are escaped.
Support/resistance lists use | as the separator within a quoted field.
""".trimIndent() + "\n"

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
    private fun num(value: Double): String = if (value.isFinite()) value.toString() else ""
    private fun isoUtc(time: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))
}
