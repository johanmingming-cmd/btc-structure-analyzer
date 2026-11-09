# Legacy Scanner Historical Replay — Research Specification

## Status and safety

- Research-only branch: `research/legacy-scanner-replay`.
- This work targets the live `CoinScanner` baseline, not `AdaptivePredictionEngine`.
- No production scanner behavior, APK, UI, or Adaptive implementation is changed.
- Do not merge this branch or build/release an APK from it without explicit approval.
- Do not run GitHub Actions until the replay implementation and focused tests are reviewed; preserve CI quota.

## Why a separate replay is required

The existing `PredictionLabCandidateComparisonTest` exercises Adaptive engine profiles. It is not a historical replay of `CoinScanner`. The old Scanner has a different decision path: 1h/15m structure alignment, a 3-closed-5m-candle momentum check, opposing 15m swing-level rejection, score ranking, top-40 liquidity preselection, top-15 output, and 15-minute per-symbol/direction journal suppression.

The live Scanner also uses current Futures USDT perpetual eligibility and current bid/ask spread. Ordinary historical OHLCV files do not contain point-in-time Futures listings or historical bid/ask quotes. Those live filters cannot honestly be reconstructed from candle-only history.

## Frozen live rules to reproduce

Source of truth: `app/src/main/java/com/btcstructureanalyzer/CoinScanner.kt`, `StructureAnalyzer.kt`, `SignalJournal.kt`, and `FuturesAutoScanService.kt`.

1. The service scans on a nominal 10-minute cycle.
2. Candidate market filter: USDT Spot pair, not BTCUSDT, excluded stablecoin/leveraged-token bases, quote volume >= $20m, absolute 24h price change <= 12%, spread <= 0.50%, and membership in the currently tradable USDT perpetual Futures set.
3. Rank eligible pairs by 24h quote volume; inspect at most 40.
4. Analyze 5m, 15m, and 1h structure. Structure pivots require two bars on each side; the newest in-progress candle is excluded. Trend is BULLISH only when the last two highs are HH and last two lows HL; BEARISH only for LH + LL.
5. Signal only when 1h and 15m trends align bullish or bearish.
6. Momentum uses the last three fully closed 5m candles: their net move must match the aligned direction and the last candle body must also match.
7. Reject if the nearest opposing 15m swing level falls within the entry band of +/- 0.025%.
8. Compute the existing bounded ranking score with the unchanged `calculateScannerScore`; it is a rank score, not a probability.
9. Sort by score descending, then quote volume descending; publish at most 15.
10. Journal suppresses another signal for the same symbol and direction for 15 minutes.
11. The app evaluates 10m, 15m, 30m and 60m outcomes using completed 1m Spot candle closes at or immediately before the target (within 60 seconds). 3h/6h are not native horizons in this Scanner journal and must be reported separately if added.

## Historical replay rules and unavoidable approximations

- Pin a fixed, explicitly listed USDT Spot symbol universe before downloading. Do not silently select symbols using today's top-volume ranking and present it as a point-in-time universe.
- Download gap-free 1m, 5m, 15m and 1h Spot candles for a declared date range. The 1m series is for outcome measurement; structure and trigger decisions use 5m/15m/1h only.
- Replay decisions on a fixed 10-minute UTC grid. Report this as a deterministic approximation of the service's nominal 10-minute cadence; the live service's phase depends on when it was started.
- Reconstruct the currently forming 5m/15m/1h candles from only the lower-timeframe bars available at each decision timestamp. Never use the final OHLC of a still-open higher-timeframe candle. This is necessary because `StructureAnalyzer.analyze` drops the newest candle as in-progress.
- Reproduce swing confirmation, 1h/15m alignment, three-closed-5m momentum, opposing-level rejection, score, top-40/top-15 ranking and 15-minute deduplication from source rules.
- Approximate rolling 24h quote volume from available OHLCV (e.g. close x base volume) and 24h price change from historical candles. Clearly label these as approximations, not Binance ticker fields.
- Historical bid/ask spread and historical Futures eligibility are unavailable in candle-only data. Do not invent them. The primary report must disclose that the spread and Futures eligibility gates are not replayed; optionally provide a stricter secondary subset only if independent historical data for those fields is obtained.
- Use only completed 1m closes whose close time is at or before the horizon target and no more than 60 seconds before it, matching the live journal's timing rule. Missing outcomes remain missing, not wins/losses.
- Apply the same direction threshold as the journal: favorable return > +0.05% is positive, below -0.05% is negative, otherwise neutral.
- Preserve every decision, rejection reason, signal, and outcome in machine-readable output so coverage and selection effects are visible.

## Evaluation design

- Keep one untouched baseline replay using the exact frozen live rules above (with declared data-only approximations).
- Predeclare Model A (momentum quality) and Model B (anti-late-entry) as separate one-factor experiments. Do not combine them until each has independent evidence.
- All variants must use the exact same symbols, timestamps, candles, scan cadence, ranking universe and outcome rules.
- Split chronologically into development and validation. A candidate may be selected on validation only; any final holdout must be fresh, sealed, and evaluated once. Never tune on a viewed holdout.
- Report per horizon: eligible market candidates, structure-aligned candidates, momentum passes, level rejections, published signals, coverage, positive/negative/neutral/missing outcomes, hit rate excluding neutral/missing, mean signed return, and counts.
- Also report LONG and SHORT separately, per-symbol counts, score bands, and market-regime context. Always show sample counts next to percentages.
- Do not use hit rate alone. Compare mean signed return and coverage, and disclose that close-to-close results omit fees, slippage, execution latency and intrabar path risk.
- No candidate is approved for production unless it improves predeclared criteria on validation and a new untouched holdout without material regression across horizons.

## Implementation gate

Before running any workflow:
1. Implement the replay in isolated research code only.
2. Add unit tests for partial-candle aggregation, no-lookahead swing confirmation, momentum window, opposing-level rejection, score ordering, top-40/top-15 selection, 15-minute deduplication, and 1m target-close selection.
3. Confirm tests do not alter the Android app or Adaptive engine.
4. Review dataset size, runtime, and artifact retention.
5. Run one manual workflow on a newly downloaded fixed-universe dataset and retain the exact data, manifest, replay logs, and report.

Until these gates pass, no historical result should be described as a Scanner backtest.
