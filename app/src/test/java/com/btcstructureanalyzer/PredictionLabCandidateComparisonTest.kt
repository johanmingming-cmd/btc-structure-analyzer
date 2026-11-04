package com.btcstructureanalyzer

import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * One-shot, fresh-dataset comparison for the three predeclared trend candidates.
 * Candidate selection uses validation only. The reserved holdout is evaluated
 * once, and only for the baseline plus the single validation-selected candidate.
 */
class PredictionLabCandidateComparisonTest {
    private data class Segment(val name: String, val start: Long, val end: Long)

    private data class Totals(
        var candidates: Int = 0,
        var signals: Int = 0,
        var hits: Int = 0,
        var misses: Int = 0,
        var neutral: Int = 0,
        var waits: Int = 0,
        var signedReturnSum: Double = 0.0
    ) {
        fun addCandidate(decision: AdaptiveDecision, signedReturn: Double?) {
            candidates++
            if (decision == AdaptiveDecision.WAIT) {
                waits++
                return
            }
            val signed = requireNotNull(signedReturn)
            signals++
            signedReturnSum += signed
            when {
                signed > 0.0 -> hits++
                signed < 0.0 -> misses++
                else -> neutral++
            }
        }
        val hitRate: Double? get() = if (signals > 0) hits * 100.0 / signals else null
        val meanSignedReturn: Double? get() = if (signals > 0) signedReturnSum / signals else null
        val coverage: Double? get() = if (candidates > 0) signals * 100.0 / candidates else null
    }

    private fun segments(candles: List<Candle>): List<Segment> {
        val start = candles.first().openTime
        val end = candles.last().closeTime + 1L
        val duration = end - start
        val warmup = start + 50L * 300_000L
        val developmentEnd = start + (duration * 0.60).toLong()
        val validationEnd = start + (duration * 0.80).toLong()
        require(warmup < developmentEnd && developmentEnd < validationEnd && validationEnd < end)
        return listOf(
            Segment("development", warmup, developmentEnd),
            Segment("validation", developmentEnd, validationEnd),
            Segment("reserved_final_holdout", validationEnd, end)
        )
    }

    private fun load(file: File): List<Candle> {
        require(file.isFile) { "Required historical dataset is missing: ${file.absolutePath}" }
        return file.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val p = line.split(",")
            require(p.size >= 7) { "Malformed CSV row: $line" }
            Candle(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(),
                p[4].toDouble(), p[5].toDouble(), p[6].toLong())
        }
    }

    private fun requireValidSeries(candles: List<Candle>, interval: Long, label: String) {
        require(candles.size >= 100) { "Insufficient $label history: ${candles.size}" }
        require(candles.zipWithNext().all { (a, b) -> b.openTime - a.openTime == interval }) {
            "Candle gap in $label"
        }
        require(candles.all { c ->
            c.openTime >= 0L && c.closeTime == c.openTime + interval - 1L &&
                c.open.isFinite() && c.high.isFinite() && c.low.isFinite() &&
                c.close.isFinite() && c.volume.isFinite() && c.open > 0.0 &&
                c.high >= maxOf(c.open, c.close) && c.low <= minOf(c.open, c.close) &&
                c.low > 0.0 && c.volume >= 0.0
        }) { "Invalid OHLCV in $label" }
    }

    private fun evaluateProfile(
        root: File,
        symbols: List<String>,
        profile: AdaptiveEngineConfig,
        segmentIndex: Int
    ): Map<Int, Totals> {
        val aggregate = mutableMapOf(12 to Totals(), 36 to Totals(), 72 to Totals())
        for (symbol in symbols) {
            val coin = File(root, symbol)
            val h1 = load(File(coin, "1h.csv"))
            val m15 = load(File(coin, "15m.csv"))
            val m5 = load(File(coin, "5m.csv"))
            val segment = segments(m5)[segmentIndex]

            // Build one hourly decision stream. Each prediction is reused across horizons;
            // 3h and 6h outcomes are sampled every 3rd and 6th hourly decision respectively.
            val allHourlyEligible = m5.indices.filter { i ->
                val decisionTime = m5[i].closeTime + 1L
                decisionTime >= segment.start && decisionTime < segment.end &&
                    i + 12 < m5.size && m5[i + 12].closeTime + 1L <= segment.end
            }
            val hourlySamples = allHourlyEligible.filterIndexed { position, _ -> position % 12 == 0 }
            val predictions = hourlySamples.associateWith { i ->
                val history = m5.subList(0, i + 1)
                val decisionTime = history.last().closeTime + 1L
                AdaptivePredictionEngine.evaluate(
                    AdaptiveEngineInput(
                        decisionTime,
                        h1.filter { it.closeTime < decisionTime },
                        m15.filter { it.closeTime < decisionTime },
                        history
                    ),
                    profile
                ).decision
            }

            for (horizon in listOf(12, 36, 72)) {
                val horizonStep = horizon / 12
                val eligible = hourlySamples.filterIndexed { position, i ->
                    position % horizonStep == 0 &&
                        i + horizon < m5.size &&
                        m5[i + horizon].closeTime + 1L <= segment.end
                }
                for (i in eligible) {
                    val decision = predictions.getValue(i)
                    val signedReturn = if (decision == AdaptiveDecision.WAIT) {
                        null
                    } else {
                        val raw = (m5[i + horizon].close / m5[i].close - 1.0) * 100.0
                        if (decision == AdaptiveDecision.LONG) raw else -raw
                    }
                    aggregate.getValue(horizon).addCandidate(decision, signedReturn)
                }
            }
        }
        return aggregate
    }

    private fun printMetrics(split: String, profile: String, metrics: Map<Int, Totals>) {
        for (horizon in listOf(12, 36, 72)) {
            val row = metrics.getValue(horizon)
            println(
                "CANDIDATE_METRIC split=$split profile=$profile horizonMinutes=${horizon * 5} " +
                    "candidates=${row.candidates} signals=${row.signals} hits=${row.hits} " +
                    "misses=${row.misses} neutral=${row.neutral} waits=${row.waits} " +
                    "coveragePct=${row.coverage} hitRatePct=${row.hitRate} " +
                    "meanSignedReturnPct=${row.meanSignedReturn}"
            )
        }
    }

    private fun improvedHorizons(candidate: Map<Int, Totals>, baseline: Map<Int, Totals>): Int =
        listOf(12, 36, 72).count { horizon ->
            val c = candidate.getValue(horizon)
            val b = baseline.getValue(horizon)
            val ch = c.hitRate
            val bh = b.hitRate
            val cr = c.meanSignedReturn
            val br = b.meanSignedReturn
            ch != null && bh != null && cr != null && br != null &&
                cr > br && ch >= bh - 5.0
        }

    private fun hasMinimumSamples(metrics: Map<Int, Totals>): Boolean =
        metrics.getValue(12).signals >= 8 &&
            metrics.getValue(36).signals >= 3 &&
            metrics.getValue(72).signals >= 2

    private fun avoidsMaterialRegression(
        candidate: Map<Int, Totals>,
        baseline: Map<Int, Totals>
    ): Boolean = listOf(12, 36, 72).all { horizon ->
        val c = candidate.getValue(horizon)
        val b = baseline.getValue(horizon)
        val ch = c.hitRate
        val bh = b.hitRate
        val cr = c.meanSignedReturn
        val br = b.meanSignedReturn
        ch != null && bh != null && cr != null && br != null &&
            cr >= br - 0.10 && ch >= bh - 5.0
    }

    @Test fun compareBaselineAndThreePredeclaredCandidatesOnOneFreshDataset() {
        val root = File(System.getenv("MULTI_HISTORY_DIR") ?: "build/multi-coin-history")
        require(root.isDirectory) { "Fresh multi-coin history directory missing: ${root.absolutePath}" }
        val manifest = JSONObject(File(root, "manifest.json").readText())
        require(manifest.optInt("schemaVersion", -1) == 1)
        require(manifest.optString("source") == "Binance Spot public klines")
        require(manifest.optInt("requestedCoins", 0) >= 10)
        require(manifest.optInt("requestedDays", 0) >= 180)
        require(manifest.getJSONArray("intervals").let { a ->
            (0 until a.length()).map { a.getString(it) }.toSet()
        } == setOf("1h", "15m", "5m"))

        val symbols = manifest.getJSONArray("symbols").let { a ->
            (0 until a.length()).map { a.getString(it) }
        }
        require(symbols.size >= 10 && symbols.distinct().size == symbols.size)
        require(symbols.all { Regex("[A-Z0-9]+USDT").matches(it) })
        val actualDirs = root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty()
        require(actualDirs == symbols.toSet()) { "Dataset folders do not match manifest symbols" }

        for (symbol in symbols) {
            val coin = File(root, symbol)
            requireValidSeries(load(File(coin, "1h.csv")), 3_600_000L, "$symbol 1h")
            requireValidSeries(load(File(coin, "15m.csv")), 900_000L, "$symbol 15m")
            requireValidSeries(load(File(coin, "5m.csv")), 300_000L, "$symbol 5m")
        }

        val defaults = AdaptiveEngineConfig()
        val profiles = linkedMapOf(
            "baseline" to defaults,
            "volume_1_50x" to defaults.copy(
                trendSetup = defaults.trendSetup.copy(minimumVolumeRatio = 1.50)
            ),
            "pullback_0_15_atr" to defaults.copy(
                trendSetup = defaults.trendSetup.copy(
                    minimumPullbackDepthAtr = 0.15,
                    pullbackLookbackBars = 3
                )
            ),
            "combined" to defaults.copy(
                trendSetup = defaults.trendSetup.copy(
                    minimumVolumeRatio = 1.50,
                    minimumPullbackDepthAtr = 0.15,
                    pullbackLookbackBars = 3
                )
            )
        )

        val reports = mutableMapOf<Pair<String, String>, Map<Int, Totals>>()
        for (splitIndex in listOf(0, 1)) {
            val splitName = if (splitIndex == 0) "development" else "validation"
            for ((profileName, config) in profiles) {
                val metrics = evaluateProfile(root, symbols, config, splitIndex)
                reports[profileName to splitName] = metrics
                printMetrics(splitName, profileName, metrics)
            }
        }

        val baselineValidation = reports.getValue("baseline" to "validation")
        val qualifying = profiles.keys.filter { it != "baseline" }.mapNotNull { name ->
            val metrics = reports.getValue(name to "validation")
            val enough = hasMinimumSamples(metrics)
            val improved = improvedHorizons(metrics, baselineValidation)
            val stable = avoidsMaterialRegression(metrics, baselineValidation)
            println("CANDIDATE_GATE profile=$name enoughSamples=$enough improvedHorizons=$improved stableAcrossHorizons=$stable")
            if (enough && improved >= 2 && stable) name else null
        }

        if (qualifying.isEmpty()) {
            println("CANDIDATE_SELECTION=NONE reason=No candidate met the predeclared validation gates; final holdout remains untouched.")
            return
        }

        val selected = qualifying.maxWithOrNull(
            compareBy<String> { improvedHorizons(reports.getValue(it to "validation"), baselineValidation) }
                .thenBy {
                    listOf(12, 36, 72).map { h ->
                        (reports.getValue(it to "validation").getValue(h).meanSignedReturn ?: 0.0) -
                            (baselineValidation.getValue(h).meanSignedReturn ?: 0.0)
                    }.average()
                }
        )!!
        println("CANDIDATE_SELECTION=$selected basis=validation_only")

        // The new dataset's reserved holdout is opened once, only after validation selection.
        val baselineHoldout = evaluateProfile(root, symbols, profiles.getValue("baseline"), 2)
        val selectedHoldout = evaluateProfile(root, symbols, profiles.getValue(selected), 2)
        printMetrics("reserved_final_holdout_once", "baseline", baselineHoldout)
        printMetrics("reserved_final_holdout_once", selected, selectedHoldout)

        val holdoutEnough = hasMinimumSamples(selectedHoldout)
        val holdoutImproved = improvedHorizons(selectedHoldout, baselineHoldout)
        val holdoutStable = avoidsMaterialRegression(selectedHoldout, baselineHoldout)
        val passed = holdoutEnough && holdoutImproved >= 2 && holdoutStable
        println(
            "FINAL_CANDIDATE_RESULT profile=$selected passed=$passed enoughSamples=$holdoutEnough " +
                "improvedHorizons=$holdoutImproved stableAcrossHorizons=$holdoutStable"
        )
        println(
            "INTERPRETATION=Research-only close-to-close evaluation; no fees, slippage, execution or path-risk model. " +
                "Hit rate is not a win probability. A passing result is a candidate for controlled implementation review, not proof of live profitability."
        )
    }
}
