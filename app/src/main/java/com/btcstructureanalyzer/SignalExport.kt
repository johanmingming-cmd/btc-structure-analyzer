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

    fun writeZip(output: OutputStream, records: List<SignalRecord>) {
        ZipOutputStream(output.buffered()).use { zip ->
            val exportedAt = System.currentTimeMillis()
            putText(zip, "signals.csv", signalsCsv(records))
            putText(zip, "evaluations.csv", evaluationsCsv(records))
            putText(zip, "signal-analysis.json", analysisJson(records, exportedAt).toString(2))
            putText(zip, "README.txt", readme(records.size, exportedAt))
            zip.finish()
        }
    }

    private fun horizons(r: SignalRecord) = listOf(
        Horizon(15, r.price15m, r.result15m, r.evaluationTime15m, r.priceSource15m),
        Horizon(30, r.price30m, r.result30m, r.evaluationTime30m, r.priceSource30m),
        Horizon(60, r.price60m, r.result60m, r.evaluationTime60m, r.priceSource60m)
    )

    private fun signalsCsv(records: List<SignalRecord>): String = buildString {
        appendLine("signal_id,cohort,signal_time_utc,symbol,direction,signal_price,score,entry_low,entry_high,stop_loss,take_profit_1,take_profit_2,supports,resistances,entry_status,result_15m,result_30m,result_60m")
        records.forEach { r ->
            appendLine(listOf(
                csv(r.id), csv(r.cohort), csv(isoUtc(r.time)), csv(r.symbol), csv(r.direction), num(r.price), r.score.toString(),
                num(r.entryLow), num(r.entryHigh), num(r.stopLoss), num(r.takeProfit1), num(r.takeProfit2),
                csv(r.supports.joinToString("|") { it.toString() }),
                csv(r.resistances.joinToString("|") { it.toString() }),
                csv(r.entryStatus), csv(r.result15m), csv(r.result30m), csv(r.result60m)
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
                val quality = when {
                    h.price == null -> "EVALUATION_PRICE_MISSING"
                    h.evaluationTime == null -> "PRICE_PRESENT_EVALUATION_TIME_NOT_STORED"
                    h.priceSource.isNullOrBlank() -> "PRICE_PRESENT_SOURCE_NOT_STORED"
                    h.priceSource != "BINANCE_SPOT_1M_CANDLE_CLOSE" -> "LEGACY_NON_HISTORICAL_EVALUATION"
                    kotlin.math.abs(h.evaluationTime - (r.time + h.minutes * 60_000L)) > 90_000L -> "EVALUATION_OUTSIDE_TARGET_WINDOW"
                    else -> "HISTORICAL_CANDLE_WITHIN_TARGET_WINDOW"
                }
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
                    .put("data_quality", when {
                        h.price == null -> "EVALUATION_PRICE_MISSING"
                        h.evaluationTime == null -> "PRICE_PRESENT_EVALUATION_TIME_NOT_STORED"
                        h.priceSource.isNullOrBlank() -> "PRICE_PRESENT_SOURCE_NOT_STORED"
                        h.priceSource != "BINANCE_SPOT_1M_CANDLE_CLOSE" -> "LEGACY_NON_HISTORICAL_EVALUATION"
                        kotlin.math.abs(h.evaluationTime - (r.time + h.minutes * 60_000L)) > 90_000L -> "EVALUATION_OUTSIDE_TARGET_WINDOW"
                        else -> "HISTORICAL_CANDLE_WITHIN_TARGET_WINDOW"
                    })
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
                .put("entry", JSONObject().put("low", r.entryLow).put("high", r.entryHigh).put("status", r.entryStatus))
                .put("stop_loss", r.stopLoss)
                .put("take_profit_1", r.takeProfit1)
                .put("take_profit_2", r.takeProfit2)
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
                "Legacy records may not store the exact evaluation timestamp or price source.",
                "New evaluations use the nearest completed Binance Spot 1m candle close around the target time. The stored evaluation timestamp is the candle close timestamp; quality is marked outside the target window when it differs by more than 90 seconds.",
                "target_time_utc is calculated from signal time and horizon; it is not the actual evaluation time.",
                "Direction correctness is derived from stored price and the existing 0.05 percent threshold; it is not evidence of a profitable trade.",
                "No historical OHLC candles are included, so TP/SL hit order cannot be determined."
            )))
            .put("signals", signals)
    }

    private fun readme(count: Int, exportedAt: Long) = """
BTC STRUCTURE ANALYZER - EXPORT DATA
Schema version: 1.0
Export time (UTC): ${isoUtc(exportedAt)}
Exported signal count: $count
Cohort metadata: LEGACY records predate prospective cohort tracking; PROSPECTIVE_V1 records were captured after prospective tracking was added. Filter by cohort for new-sample analysis.

FILES
- signals.csv: one row per stored signal.
- evaluations.csv: one row per signal and horizon (15, 30, 60 minutes).
- signal-analysis.json: structured archive including limitations and derived fields.
- README.txt: this documentation.

DATA SAFETY
This export reads the local signal journal and does not write to or delete it.
The ZIP contains no API keys or credentials.

TIME FIELDS
signal_time_utc is the stored signal creation time.
target_time_utc is calculated as signal time + 15/30/60 minutes.
evaluation_time_utc is the timestamp of the historical 1m candle close used for evaluation.
Legacy records may lack evaluation timestamps or source metadata; new records store both.
data_quality identifies missing metadata and evaluations outside the +/-90 second target window.
All timestamps included here are UTC.

EVALUATION FIELDS
price_change = evaluation price - signal price.
evaluation_price_change_pct = (evaluation price - signal price) / signal price * 100.
direction_favorable_change_pct is positive when movement favors the stored LONG/SHORT direction.
direction_correct is true above +0.05%, false below -0.05%, and null within the threshold
or when evaluation price is missing. This derived field is not a trade-profit result.
raw_result preserves the original journal result (e.g. BENAR, SALAH, NETRAL, PENDING).
Missing prices are blank in CSV and null in JSON; they are never converted to zero.

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
