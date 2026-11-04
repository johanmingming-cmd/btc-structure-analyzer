package com.btcstructureanalyzer

import org.junit.Assert.assertTrue
import org.junit.Test

class CoinScannerScoreTest {
    @Test fun candidateRankingIsBoundedAndNotAProbability() {
        val score = calculateCandidateRankingScore(
            quoteVolume = 20_000_000.0,
            netMove = 0.0002,
            referenceOpen = 0.1,
            lastCandleVolume = 100.0,
            previousAverageVolume = 100.0,
            opposingRoomPct = 0.2
        )
        assertTrue(score in 50..100)
    }

    @Test fun strongerMarketActivityInputsIncreaseCandidateRanking() {
        val low = calculateCandidateRankingScore(
            quoteVolume = 20_000_000.0,
            netMove = 0.0001,
            referenceOpen = 0.1,
            lastCandleVolume = 100.0,
            previousAverageVolume = 100.0,
            opposingRoomPct = 0.2
        )
        val high = calculateCandidateRankingScore(
            quoteVolume = 1_000_000_000.0,
            netMove = 0.001,
            referenceOpen = 0.1,
            lastCandleVolume = 300.0,
            previousAverageVolume = 100.0,
            opposingRoomPct = 1.0
        )
        assertTrue(high > low)
    }

    @Test fun invalidOrMissingInputsKeepCandidateRankingBounded() {
        val score = calculateCandidateRankingScore(
            quoteVolume = Double.NaN,
            netMove = Double.NaN,
            referenceOpen = 0.0,
            lastCandleVolume = Double.NaN,
            previousAverageVolume = 0.0,
            opposingRoomPct = null
        )
        assertTrue(score in 50..100)
    }
}
