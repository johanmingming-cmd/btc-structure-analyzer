# Prediction Lab — Stage B1 Algorithm Audit

**Audit target:** Prediction Lab 1.5.2 source on `fix/prediction-lab-audit-2026`  
**Scope:** Read-only audit of the current signal-generation path.  
**Status:** Audit findings documented; no production source files changed.  
**User-selected objective:** prioritize directional accuracy, adaptive filtering, flexible forecast horizon.

## Executive finding

Version 1.5.2 improved reliability around journaling, historical evaluation, scanner scoring and data handling, but it did **not** replace the core decision model. The active scanner still uses a delayed swing-structure classification on 1H and 15M, requires those two timeframes to agree, then checks a very simple three-closed-candle 5M momentum condition. The displayed score is a bounded ranking heuristic, not a calibrated estimate of directional accuracy.

This means the user's concern is valid: the latest release can change which candidates pass and how they are ranked, while retaining much of the old analytical philosophy. It cannot be described as a wholly new prediction engine.

## Current decision path (verified in source)

1. Fetch Binance Spot 24-hour tickers.
2. Restrict the universe to USDT Spot symbols also present in a Binance USDⓈ-M tradable USDT perpetual whitelist; exclude selected stablecoins, leveraged tokens and BTCUSDT.
3. Apply initial candidate conditions:
   - quote volume at least 20,000,000 USDT;
   - last price positive;
   - absolute 24-hour change no more than 12%;
   - bid/ask spread no more than 0.50%.
4. Sort by quote volume and inspect at most 40 candidates.
5. Fetch 80 candles each for 5m, 15m and 1h from Binance Spot.
6. Structure analyzer excludes the newest (potentially open) candle and confirms pivots using two candles on the left and two on the right.
7. The scanner only proceeds when 1h and 15m are both bullish or both bearish. A 5m structure classification is calculated and stored, but is not itself required to agree.
8. Momentum gate uses the net move from the first open to last close across the last three closed 5m candles, plus the final candle's body direction.
9. Reject if the nearest opposing 15m swing level lies inside a narrow 0.025% entry band.
10. Rank passing candidates with a score from 50 to 100 and return up to the requested limit.

## Detailed findings

### B1-01 — Confirmed swing pivots are inherently delayed
**Evidence:** `StructureAnalyzer.analyze()` uses LEFT=2 and RIGHT=2, and only analyzes candles excluding the newest candle. A pivot is not known until two later candles have closed. This reduces premature pivot labeling but creates confirmation latency (up to two full candles on the relevant timeframe). On 1h, that can be a meaningful delay.

**Risk:** Trend continuation entries can arrive late; a reversal may already be underway before the old structure is replaced.

**Design implication:** Keep confirmed pivots as one source of structural evidence, but add a separate early-warning / developing-structure layer that is explicitly marked unconfirmed and cannot independently trigger a trade direction.

### B1-02 — 1h/15m hard agreement is too rigid for adaptive market states
**Evidence:** Scanner continues only if both 1h and 15m are bullish or both bearish. Mixed, sideways and unconfirmed states are all rejected before the 5m momentum and scoring stages.

**Risk:** This suppresses countertrend risk, but also misses early trend transitions and valid pullback/reversal setups. It does not adapt its rule set to trending, ranging or transition regimes.

**Design implication:** Use 1h as context/regime, 15m as setup structure, and 5m as trigger. Permit different setup families by regime, while retaining WAIT when evidence is incomplete.

### B1-03 — 5m momentum confirmation is underspecified
**Evidence:** Momentum is only `last.close - first.open` over three closed 5m candles, and the last candle must have the same body direction. No normalized volatility threshold, candle quality, rejection/wick analysis, momentum persistence, or breakout retest is included.

**Risk:** A small move can pass if its sign is correct; a mature move can pass late; a wick rejection or exhaustion context is not distinguished from healthy continuation.

**Design implication:** Evaluate normalized momentum (e.g. ATR-relative), body/wick quality, volume participation and distance from nearby structure. Treat these as evidence, not a simple indicator vote.

### B1-04 — Scanner score is not directional confidence
**Evidence:** `calculateScannerScore()` starts at 50 and adds:
- up to 20 points for quote liquidity;
- up to 15 for absolute net movement;
- up to 10 for last-candle volume expansion;
- up to 5 for opposing-side room (or a default 2.5 when unavailable).

The momentum contribution uses absolute movement, so it rewards magnitude rather than whether direction is likely to continue. The score has no calibration against past outcomes.

**Risk:** A liquid, active coin can receive a high score even when the directional thesis is weak or near exhaustion. A score of 71 does not mean 71% probability of success.

**Design implication:** Split into (a) setup quality, (b) directional confidence, and (c) data/confirmation status. Only call a value a probability after out-of-sample calibration demonstrates that interpretation.

### B1-05 — No explicit market-regime classifier
**Evidence:** Active scanner has no explicit regime state such as trend, range, transition or volatility shock. The current structure states (bullish/bearish/sideways/unconfirmed) do not provide a dedicated regime-aware policy.

**Risk:** One fixed gate is applied across different market environments.

**Design implication:** Add a regime classifier using interpretable price/volatility/ADX-style features and test each regime separately. Regime thresholds must be calibrated, not guessed.

### B1-06 — No explicit reversal / failed-breakout setup
**Evidence:** The active signal path does not identify liquidity sweeps, failed breakouts, reclaim/rejection confirmation, or a sequence of break-of-structure / change-of-character events as separate setup families.

**Risk:** Early reversals may be ignored until confirmed pivots update; apparent continuation may be taken without checking for a failed breakout or nearby exhaustion.

**Design implication:** Implement separate continuation, pullback and reversal hypotheses. Require a closed-candle trigger and invalidation condition for each; otherwise return WAIT.

### B1-07 — Universe filter is narrower than Spot universe
**Evidence:** Candidate Spot symbols must also exist in a tradable Binance USDⓈ-M USDT perpetual whitelist.

**Risk:** Spot assets without a qualifying perpetual contract are excluded, despite the app describing a Spot scanner.

**Design implication:** If product intent is broad Binance Spot coverage, use Spot exchangeInfo/trading status as the eligibility source. Keep any Futures cross-reference as optional metadata, not a mandatory gate.

### B1-08 — Price-level deduplication is not scale-safe
**Evidence:** Support/resistance levels are deduplicated with `distinctBy { (it * 100.0).toLong() }`.

**Risk:** For low-priced assets, distinct prices within the same 0.01 price unit collapse into one bucket. This can discard meaningful nearby structure and distort available-room checks.

**Design implication:** Deduplicate levels with a relative/ATR-based tolerance rather than multiplying all assets by 100.

### B1-09 — The three-step forecast is a heuristic, not a horizon model
**Evidence:** `StructureAnalyzer.forecast()` combines 1h/15m/5m trend votes with a small four-candle momentum sum, then emits the same direction for three steps with a decreasing heuristic score. The code explicitly describes it as a transparent heuristic, not trained ML or validated probability.

**Risk:** It does not estimate a distribution of outcomes or adapt the horizon to market volatility/regime. The three steps should not be interpreted as three independently validated future candles.

**Design implication:** Make horizon selection explicit and regime/volatility-aware, and validate each horizon separately. Until validated, present scenario labels rather than probabilistic predictions.

### B1-10 — Evaluation outcome is a narrow direction label
**Evidence:** Historical evaluation compares the signal price with a completed 1m Spot candle close near each target horizon, using a fixed favorable move threshold of +/-0.05%.

**Risk:** This is useful for directional diagnostics but does not measure execution, fees, slippage, path risk, maximum adverse excursion or tradable profitability. The +/-0.05% threshold may be too small relative to noise for some assets and regimes.

**Design implication:** Keep the existing directional metric for continuity, but add separate research metrics (volatility-normalized movement, excursion, calibration, coverage and per-regime performance). Do not reintroduce TP/SL as a required signal target.

## What 1.5.2 did improve (and what it did not)

**Improved engineering foundations:**
- safer journal parsing and recovery behavior;
- synchronized/committed journal writes and bounded history;
- historical candle endpoint fallback and completed-candle selection;
- fixes to momentum/scoring implementation and low-price handling in the analysis code;
- reduced duplicate signal recording;
- more diagnostic counts for scanner filtering.

**Not replaced:**
- swing-pivot structural method;
- 1h + 15m hard directional agreement;
- simple 3-candle 5m momentum gate;
- heuristic score construction;
- lack of explicit market-regime, pullback, reversal and false-breakout engines.

## Stage B design requirements (derived from the user's choices)

- **Objective:** optimize directional accuracy first; do not optimize signal count.
- **Filtering:** adaptive by market regime and volatility, with WAIT as a valid outcome.
- **Horizon:** flexible; choose/score horizons independently rather than assuming one fixed target.
- Separate setup quality from directional confidence.
- Keep LONG/SHORT decisions evidence-based; never force a direction.
- No claim of a probability or win rate without out-of-sample calibration.
- Preserve the existing 1.5.2 APK and its data as the baseline/control.
- Build and test a new engine in an isolated branch; do not merge or replace the installed APK without explicit approval.

## Recommended B2 architecture (proposal, not implementation)

1. **Data quality layer:** synchronized closed Spot candles, gap/staleness checks, exchange symbol eligibility.
2. **Regime layer:** trend / range / transition / volatility shock.
3. **Structure layer:** confirmed swing structure plus developing structure, break/reclaim and invalidation.
4. **Setup layer:** continuation, pullback and reversal hypotheses kept distinct.
5. **Momentum/participation layer:** ATR-normalized displacement, candle body/wick quality, relative volume and persistence.
6. **Location/risk-context layer:** support/resistance distance using relative/ATR-aware level clustering.
7. **Decision layer:** LONG / SHORT / WAIT with explicit reason codes.
8. **Scoring layer:** separate setup quality and directional confidence; calibrate only after historical out-of-sample tests.
9. **Horizon layer:** evaluate multiple candidate horizons and select only those with sufficient evidence.
10. **Evaluation layer:** per-horizon, per-regime, per-setup and per-direction reports; maintain a strict version/cohort boundary.

## Validation gates before implementation can be called complete

- Deterministic unit tests for pivots, regime states, breakouts, pullbacks, reversal triggers, low-priced assets and missing/stale candles.
- No look-ahead: all decisions use only candles closed at the signal timestamp.
- Chronological train/validation/test split; no random shuffle across time.
- Compare old and new engines on the same symbols and timestamps.
- Report signal coverage, directional accuracy, neutral rate, false-signal rate, per-regime results and confidence calibration.
- Do not promote the new engine unless it improves the pre-agreed primary accuracy metric without unacceptable collapse in coverage.
- Keep source changes isolated and APK package/signing compatibility verified before any user installation.

## Files inspected

- `StructureAnalyzer.kt`
- `CoinScanner.kt`
- `BinanceRepository.kt`
- `SignalJournal.kt`
- `MainActivity.kt` (entry/activity state and scanner integration context)

**Important:** This document records a source-level audit. It does not claim a new engine has been implemented, backtested or proven more accurate.
