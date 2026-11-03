package com.btcstructureanalyzer

enum class SelectedSetupFamily { TREND_CONTINUATION, TREND_PULLBACK, RANGE_REVERSAL, NONE }
enum class ArbitrationReason { SELECTED_SINGLE_SETUP, CONFLICTING_DIRECTIONS, NO_CONFIRMED_SETUP }

data class ArbitrationResult(
    val decision: AdaptiveDecision,
    val family: SelectedSetupFamily,
    val qualityScore: Int,
    val reason: ArbitrationReason,
    val explanation: String
)

/** Never resolves opposing confirmed setups by score; conflict is always WAIT. */
object SetupArbitrator {
    fun select(
        trend: SetupDetectionResult,
        range: RangeDetectionResult
    ): ArbitrationResult {
        val candidates = buildList {
            if (trend.status == SetupStatus.CONFIRMED && trend.direction != AdaptiveDecision.WAIT) {
                val family = when (trend.family) {
                    SetupFamily.TREND_CONTINUATION -> SelectedSetupFamily.TREND_CONTINUATION
                    SetupFamily.TREND_PULLBACK -> SelectedSetupFamily.TREND_PULLBACK
                    SetupFamily.NONE -> SelectedSetupFamily.NONE
                }
                if (family != SelectedSetupFamily.NONE) add(Triple(trend.direction, family, trend.qualityScore))
            }
            if (range.status == SetupStatus.CONFIRMED && range.direction != AdaptiveDecision.WAIT) {
                add(Triple(range.direction, SelectedSetupFamily.RANGE_REVERSAL, range.qualityScore))
            }
        }
        if (candidates.isEmpty()) return ArbitrationResult(
            AdaptiveDecision.WAIT, SelectedSetupFamily.NONE, 0,
            ArbitrationReason.NO_CONFIRMED_SETUP, "Tidak ada setup yang lolos konfirmasi; WAIT."
        )
        if (candidates.map { it.first }.distinct().size > 1) return ArbitrationResult(
            AdaptiveDecision.WAIT, SelectedSetupFamily.NONE, 0,
            ArbitrationReason.CONFLICTING_DIRECTIONS,
            "Setup terkonfirmasi saling berlawanan; sistem tidak memilih berdasarkan skor dan menahan sinyal."
        )
        val selected = candidates.maxBy { it.third }
        return ArbitrationResult(
            selected.first, selected.second, selected.third,
            ArbitrationReason.SELECTED_SINGLE_SETUP,
            "Satu arah setup terkonfirmasi dipilih: ${selected.second}."
        )
    }
}
