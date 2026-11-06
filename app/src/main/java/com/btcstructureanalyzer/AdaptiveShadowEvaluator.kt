package com.btcstructureanalyzer

/**
 * Isolated shadow result for the adaptive engine.
 *
 * This model is deliberately not persisted to SignalJournal and cannot alter
 * the legacy scanner's published direction. It is the boundary used by the
 * scanner integration in a later slice.
 */
data class AdaptiveShadowRecord(
    val symbol: String,
    val referencePrice: Double,
    val decisionTime: Long,
    val legacyDirection: AdaptiveDecision,
    val adaptive: AdaptiveEngineResult,
    val engineVersion: String = adaptive.engineVersion
) {
    init {
        require(symbol.isNotBlank())
        require(referencePrice.isFinite() && referencePrice > 0.0)
        require(decisionTime > 0L)
        require(adaptive.decisionTime == decisionTime) {
            "Shadow and adaptive decision timestamps must match"
        }
    }

    val agreesWithLegacy: Boolean?
        get() = when {
            adaptive.decision == AdaptiveDecision.WAIT -> null
            legacyDirection == AdaptiveDecision.WAIT -> null
            else -> adaptive.decision == legacyDirection
        }

    val comparison: ShadowComparison
        get() = when {
            adaptive.decision == AdaptiveDecision.WAIT -> ShadowComparison.ADAPTIVE_WAIT
            legacyDirection == AdaptiveDecision.WAIT -> ShadowComparison.LEGACY_WAIT
            adaptive.decision == legacyDirection -> ShadowComparison.AGREE
            else -> ShadowComparison.DISAGREE
        }
}

enum class ShadowComparison { AGREE, DISAGREE, ADAPTIVE_WAIT, LEGACY_WAIT }

/** Pure adapter: callers supply an already aligned, closed-candle snapshot. */
object AdaptiveShadowEvaluator {
    fun evaluate(
        symbol: String,
        referencePrice: Double,
        legacyDirection: AdaptiveDecision,
        input: AdaptiveEngineInput,
        config: AdaptiveEngineConfig = AdaptiveEngineConfig()
    ): AdaptiveShadowRecord {
        require(symbol.isNotBlank())
        require(referencePrice.isFinite() && referencePrice > 0.0)
        val result = AdaptivePredictionEngine.evaluate(input, config)
        return AdaptiveShadowRecord(
            symbol = symbol.trim().uppercase(),
            referencePrice = referencePrice,
            decisionTime = input.decisionTime,
            legacyDirection = legacyDirection,
            adaptive = result
        )
    }
}
