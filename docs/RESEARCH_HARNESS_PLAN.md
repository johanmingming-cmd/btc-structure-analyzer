# Prediction Lab Research Harness (Experimental)

## Scope and safety boundary

This branch is isolated from the installed 1.5.2 APK and the existing B3 implementation branch. It is research tooling only. It does not change live scanner behavior, UI, production defaults, or release artifacts. No branch merge or release is authorized by this work.

## Diagnostic reporting

Detector WAIT summaries are retained for compatibility, while a second diagnostic detail identifies the first failing condition. Range diagnostics distinguish missing boundary sweeps, excessive penetration, failed reclaim, insufficient wick/body, insufficient volume, ambiguous boundaries, and missing/weak 5m confirmation. Trend diagnostics distinguish weak/wrong-direction 15m candles, missing breakout/pullback patterns, missing 5m trigger, weak trigger body, and low 5m volume.

These details are observational only: they must not change whether the detector returns LONG, SHORT, or WAIT.

## Dataset reproducibility

The downloader supports:
- `MULTI_HISTORY_END_TIME_MS` to pin the request end timestamp.
- `MULTI_HISTORY_SYMBOLS` to pin an explicit ordered USDT symbol list.
- Manifest coverage per symbol and interval, including bar count, first open timestamp, and last close timestamp.
- A manifest note explaining whether the universe was explicitly pinned or selected from current 24-hour quote volume.

The default live-volume universe is a convenience sample, not a point-in-time historical universe. Results must be described as conditional on the selected current universe; they must not be presented as free of survivorship or universe-selection bias. The denylist is maintained and covered by tests; it is not a universal classification of every stablecoin.

A single downloaded dataset should be reused for all profiles being compared. Do not redownload between parameter profiles.

## Chronological evaluation

The segment evaluator allows all earlier candles as warm-up history but only scores decisions inside a specified time window. A forward outcome is eligible only if its closing timestamp remains inside that same segment. It never passes future candles to the predictor.

Target split for a 180-day dataset:
- Development: first 60% (about 108 days), after a warm-up of at least 50 5m candles.
- Validation: next 20% (about 36 days), used to confirm a candidate selected on development data.
- Final holdout: last 20% (about 36 days), opened only after the candidate and evaluation rules are frozen.

For each horizon, use non-overlapping outcome windows as the primary comparison (evaluation step equal to horizon bars). More frequent overlapping samples may be supplementary only and must be labeled accordingly.

## Metrics and interpretation

Report pooled candidate count, signal count, WAIT count, coverage, hit/miss/neutral counts, hit rate, mean signed close-to-close return, and per-coin consistency. Always show sample size. Hit rate is not a win probability and close-to-close returns omit fees, slippage, execution, and path risk. Do not select a profile using final-holdout results.

## Current implementation status

- [x] Create isolated experiment branch from B3 head.
- [x] Add detector-level WAIT sub-reason diagnostics without changing default thresholds.
- [x] Add focused unit tests for selected diagnostic paths.
- [x] Add fixed-universe and fixed-end downloader controls.
- [x] Record exact candle coverage in the manifest.
- [x] Add a chronological segment evaluator with segment-contained outcomes.
- [ ] Complete development/validation/holdout orchestration in the historical multi-coin test.
- [ ] Run and inspect the focused unit-test workflow.
- [ ] Add historical-run artifact retention so the exact downloaded dataset and report can be reused.
- [ ] Review results before proposing any algorithm change.

## Validation commands

The unit-test workflow runs Python downloader tests and focused Kotlin detector/engine/evaluator tests. It deliberately does not build or sign an APK.
