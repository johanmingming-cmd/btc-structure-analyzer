package com.btcstructureanalyzer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class SignalExportTest {
    private fun signal(id: String, cohort: String) = SignalRecord(
        id = id,
        time = 0L,
        symbol = "BTCUSDT",
        direction = "LONG",
        price = 100.0,
        entryLow = 99.0,
        entryHigh = 101.0,
        stopLoss = 95.0,
        takeProfit1 = 105.0,
        takeProfit2 = 110.0,
        score = 70,
        supports = emptyList(),
        resistances = emptyList(),
        price15m = 101.0,
        price30m = 102.0,
        price60m = 103.0,
        result15m = "BENAR",
        result30m = "BENAR",
        result60m = "BENAR",
        evaluationTime15m = 900_000L,
        evaluationTime30m = 1_800_000L,
        evaluationTime60m = 3_600_000L,
        priceSource15m = "BINANCE_SPOT_1M_CANDLE_CLOSE",
        priceSource30m = "BINANCE_SPOT_1M_CANDLE_CLOSE",
        priceSource60m = "BINANCE_SPOT_1M_CANDLE_CLOSE",
        cohort = cohort
    )

    @Test
    fun exportMarksLegacyRowsNotProspectiveAndPreservesProspectiveQuality() {
        val output = ByteArrayOutputStream()
        SignalExport.writeZip(
            output,
            listOf(signal("old", "LEGACY"), signal("new", "PROSPECTIVE_V1"))
        )

        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }

        val evaluations = entries.getValue("evaluations.csv")
        assertTrue(evaluations.contains("LEGACY_COHORT_NOT_PROSPECTIVE"))
        assertTrue(evaluations.contains("HISTORICAL_CANDLE_WITHIN_TARGET_WINDOW"))
        assertTrue(entries.getValue("signals.csv").contains("\"LEGACY\""))
        assertTrue(entries.getValue("signals.csv").contains("\"PROSPECTIVE_V1\""))
        assertTrue(entries.getValue("signal-analysis.json").contains("\"cohort\": \"LEGACY\""))
        assertTrue(entries.getValue("signal-analysis.json").contains("\"cohort\": \"PROSPECTIVE_V1\""))
    }
}
