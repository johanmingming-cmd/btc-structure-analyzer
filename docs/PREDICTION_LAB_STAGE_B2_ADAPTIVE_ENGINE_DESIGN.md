# Prediction Lab — Stage B2 Adaptive Prediction Engine Design

**Status:** Design specification only; not implemented.
**Parent audit:** Stage B1, commit `e6a20caaaab5c695b6cfefbd6568191c92e3348f`.
**Target baseline:** Prediction Lab 1.5.2 on `fix/prediction-lab-audit-2026`.
**User priorities:** directional accuracy first; adaptive filtering; flexible horizon.
**Safety boundary:** no TP/SL requirement, no automated trading, no APK replacement, no production merge in this stage.

## 1. Product objective and decision contract

The engine is a research-grade signal classifier for Binance Spot USDT markets. Its output is a conditional directional hypothesis, not a promise of profit.

Every evaluated symbol must resolve to exactly one of:
- `LONG`: sufficient closed-candle evidence for an upward directional hypothesis.
- `SHORT`: sufficient closed-candle evidence for a downward directional hypothesis.
- `WAIT`: evidence is incomplete, conflicting, low quality, stale, or below the regime-specific acceptance threshold.

The engine must never force a LONG/SHORT merely to increase signal frequency. Accuracy is the primary objective; coverage is a guardrail and secondary objective.

A result must carry: symbol, signal timestamp, reference price, regime, setup family, direction, selected horizon(s), setup-quality score, directional-evidence score, data-quality state, reason codes, and engine version. Scores are ordinal research scores until calibration proves otherwise; they must not be labeled percentages or win probabilities.

## 2. Data contract and time integrity

### 2.1 Market scope
- Use Binance Spot USDT symbols that are actively trading on Spot.
- Spot exchange metadata is the eligibility authority. A USDⓈ-M perpetual listing may be retained as optional metadata but must not be a mandatory eligibility gate.
- Exclude stablecoins, leveraged tokens and other explicitly configured unsuitable instruments using a maintained, testable rule set.
- Do not hard-exclude BTCUSDT unless the product setting explicitly says so.

### 2.2 Candle contract
Use closed candles only for every timeframe. Each candle needs open time, close time, OHLCV and quote-volume where available.

Before analysis, verify:
- timestamps are strictly increasing and unique;
- expected interval continuity is within a documented tolerance;
- the latest candle is closed at the decision timestamp;
- all timeframe snapshots are aligned to the same decision cut-off;
- no future candle or future-derived value enters a feature.

A missing, stale, duplicated or misaligned required series produces `WAIT_DATA`, not a directional signal. Record the precise reason.

### 2.3 Initial timeframes
Keep 1H / 15M / 5M as the initial context/setup/trigger stack:
- 1H: broad context and regime evidence.
- 15M: structure and setup formation.
- 5M: trigger and timing only.

The 5M timeframe must not independently reverse the 1H context. Mixed timeframes are not an automatic rejection; their meaning depends on regime and setup family.

## 3. Regime classifier

Classify the current market state into one of four mutually exclusive states:
1. `TREND`: directional persistence and structure are present.
2. `RANGE`: price is rotating between boundaries without reliable directional expansion.
3. `TRANSITION`: trend/range evidence is mixed or a structural state change is developing.
4. `VOLATILITY_SHOCK`: volatility expands abruptly or data becomes unstable enough that ordinary thresholds are unreliable.

Use interpretable features only in the first version:
- ADX / directional movement as trend-strength evidence;
- EMA slope and price location as directional context;
- ATR percentage and recent ATR expansion as volatility context;
- normalized range efficiency / overlap as range-versus-trend evidence;
- swing sequence and close-based break/reclaim as structure evidence.

No single indicator is a regime oracle. Regime is selected by a documented feature rule or a transparent weighted vote with an explicit `TRANSITION` fallback. Thresholds must be learned on training data and frozen before validation/test. Do not tune them on the final test period.

Regime policy:
- TREND: continuation is eligible; pullback is eligible when retracement stabilizes and trigger resumes trend.
- RANGE: mean-reversion hypotheses may be evaluated only near validated range boundaries with rejection/return confirmation. Mid-range is WAIT.
- TRANSITION: require stronger multi-layer agreement and a closed-candle structural trigger; otherwise WAIT.
- VOLATILITY_SHOCK: default WAIT until volatility and candle continuity normalize. Do not lower thresholds to manufacture signals.

## 4. Setup families (independent hypotheses)

Do not collapse all setups into one direction vote. Evaluate each family independently, then arbitrate.

### 4.1 Trend continuation
Required evidence:
- 1H context supports a direction and is not a volatility shock.
- 15M structure has a sequence consistent with that direction or a valid close-based break.
- 5M trigger closes in the intended direction with adequate body quality and normalized displacement.
- Price is not already excessively extended from its 15M structure / volatility envelope.
- No nearby opposing structure invalidates the available movement context.

A confirmed swing pivot is supporting evidence, not the only source of structure. Developing pivots can warn but cannot independently confirm a setup.

### 4.2 Pullback continuation
Required evidence:
- a valid 1H directional context exists;
- 15M retracement moves against the context but does not invalidate the relevant protected swing / structure;
- retracement reaches a meaningful structure or volatility-relative area;
- 5M closes back in the context direction with participation or momentum recovery.

A pullback is not a reversal merely because 15M or 5M temporarily points against 1H.

### 4.3 Reversal
Required evidence:
- prior directional movement or a meaningful range boundary is identifiable;
- a sweep / failed break / exhaustion clue is observed at a relevant location;
- a reclaim or break-of-structure is confirmed by a closed candle;
- a subsequent trigger confirms the new direction;
- invalidation level and opposing structure are recorded.

Do not label a reversal from one wick, one oscillator extreme, or an unconfirmed developing pivot. Early reversal warnings may be shown as `WATCH`, never as confirmed LONG/SHORT.

### 4.4 Range rejection (range regime only)
Required evidence:
- regime is RANGE;
- price is near a robust range boundary, not the midpoint;
- rejection is confirmed by candle close back inside the range;
- the next trigger supports movement away from the boundary;
- range integrity remains intact.

If the range breaks and holds outside, stop treating it as a rejection and reassess as TRANSITION or TREND.

## 5. Feature normalization and evidence

All movement/level distances should be normalized to ATR or price percentage, not fixed absolute price increments.

Candidate evidence groups:
- **Structure:** confirmed swing sequence, close-based break/reclaim, protected swing integrity, distance to opposing structure.
- **Momentum:** ATR-normalized displacement over multiple closed bars, persistence, acceleration/deceleration.
- **Candle quality:** body-to-range ratio, upper/lower wick ratios, close location within candle.
- **Participation:** relative volume against a rolling baseline; volume is supporting evidence, never mandatory when reliable volume is unavailable.
- **Location:** distance to support/resistance and setup invalidation in ATR units.
- **Context:** 1H direction, 15M setup alignment, 5M trigger state.
- **Data quality:** completeness, freshness and timeframe alignment.

Missing optional evidence is marked unavailable and must not be silently treated as positive. Missing critical evidence blocks the signal. Avoid double-counting correlated indicators: for example, EMA slope and moving-average direction should belong to one context evidence group.

## 6. Scoring and decision arbitration

Maintain separate outputs:

### 6.1 Setup quality (0–100 ordinal)
Measures whether the pattern is well-formed: location, structural clarity, trigger quality, invalidation clarity and data completeness.

### 6.2 Directional evidence (signed ordinal, -100 to +100)
Measures net evidence for SHORT versus LONG. It is not a probability. Report positive evidence and negative/conflicting evidence separately in diagnostics.

### 6.3 Acceptance policy
- Apply regime- and setup-specific thresholds learned only on training data.
- Require minimum evidence from independent groups; a high aggregate score cannot compensate for absent critical structure or trigger evidence.
- Require an explicit trigger candle close.
- Reject if the invalidation condition has already occurred.
- Return WAIT when LONG and SHORT hypotheses are both weak or similarly supported.
- If multiple setup families qualify in opposite directions, return WAIT_CONFLICT unless a pre-registered arbitration rule resolves the conflict.

The initial research version should prefer transparent rules over opaque ML. Do not introduce a learned classifier until a rule-based benchmark and leakage-safe dataset exist.

## 7. Flexible horizon selection

Evaluate a fixed research grid of forward horizons independently (initial proposal: 3, 6, 12 and 24 completed 5-minute bars after the signal timestamp). This is a measurement grid, not a promise that the app predicts exactly those candles.

For each eligible setup and direction:
- estimate historical directional outcome separately at each horizon;
- normalize forward movement by ATR at the signal timestamp;
- report the proportion of cases reaching the pre-registered favorable directional threshold, adverse movement distribution and neutral outcome rate;
- select only horizon(s) that pass minimum sample size and validation acceptance criteria;
- allow no selected horizon; return WAIT / insufficient horizon evidence.

Do not copy one direction into every future step with a mechanically decreasing score. Do not select the best horizon by looking at test-set results. Horizon selection rules must be frozen before final test.

## 8. Decision reason codes and UI-facing states

Minimum reason codes:
- `WAIT_DATA_STALE`
- `WAIT_DATA_GAP`
- `WAIT_DATA_MISALIGNED`
- `WAIT_REGIME_UNCLEAR`
- `WAIT_VOLATILITY_SHOCK`
- `WAIT_NO_SETUP`
- `WAIT_TRIGGER_UNCONFIRMED`
- `WAIT_CONFLICTING_EVIDENCE`
- `WAIT_EXTENDED_MOVE`
- `WAIT_OPPOSING_STRUCTURE`
- `WAIT_INSUFFICIENT_HISTORY`
- `WAIT_HORIZON_UNVALIDATED`

Directional result must show a short explanation: regime, setup family, 2–4 strongest supporting facts, strongest opposing fact, trigger candle time, invalidation condition, and selected horizon(s). Never show TP/SL as a mandatory output.

## 9. Historical evaluation protocol

### 9.1 Dataset and split
- Build a timestamped dataset from historical Spot candles and reproduce the exact information available at each signal time.
- Split chronologically into train / validation / untouched test periods.
- Apply a purge/embargo at split boundaries at least as long as the maximum evaluated horizon, so labels cannot leak across partitions.
- Keep symbol and timestamp cohorts identical for old-versus-new comparisons.
- Record data-source gaps and delisted symbols; do not silently drop difficult periods.

### 9.2 Baselines
Compare:
1. current 1.5.2 engine;
2. adaptive rule engine;
3. simple non-directional baseline (always WAIT) for coverage context.

Run ablations removing one evidence group at a time to identify whether a feature adds genuine out-of-sample value.

### 9.3 Primary and guardrail metrics
Primary metric: directional accuracy on accepted directional signals, calculated per horizon and overall with sample counts and confidence intervals.

Guardrails / diagnostics:
- signal coverage (signals / eligible decision opportunities);
- LONG and SHORT counts and accuracy separately;
- WAIT / neutral rate;
- false-signal rate;
- per-regime and per-setup accuracy;
- performance by volatility bucket and liquidity bucket;
- outcome movement in ATR units;
- maximum favorable and adverse excursion as research diagnostics, not TP/SL requirements;
- calibration / reliability only if scores are later mapped to probabilities;
- stability across symbols and time periods.

Always display denominator and sample size. A high accuracy from a tiny number of signals is not sufficient evidence.

### 9.4 Promotion gate
Do not promote solely because aggregate accuracy rises. Require:
- no look-ahead/leakage tests pass;
- deterministic unit tests pass;
- test-set directional accuracy improvement versus 1.5.2 is statistically and practically credible;
- coverage remains above a pre-agreed minimum;
- no major regime/setup subgroup collapses without an explicit accepted trade-off;
- performance is not driven by one symbol or one short market period;
- thresholds and code are frozen before final test.

If evidence is inconclusive, keep the existing engine and label the new result experimental.

## 10. Required test matrix

- candle boundary: open candle excluded; close-time boundary exact;
- missing, duplicated, stale and out-of-order candles;
- timeframe alignment and data gaps;
- pivot confirmation latency and developing-pivot non-confirmation;
- trend continuation with clean and exhausted moves;
- pullback that holds structure versus pullback that breaks protected structure;
- sweep-and-reclaim reversal versus wick-only false reversal;
- range boundary rejection versus mid-range noise;
- range breakout and hold;
- volatility shock and normalization;
- low-priced and high-priced symbols with scale-invariant level clustering;
- Spot-only symbols with no Futures perpetual;
- opposing nearby structure and invalidation already hit;
- conflicting setup families;
- insufficient history / missing volume;
- horizon labels at exact forward candle boundaries;
- chronological split and purge/embargo behavior.

## 11. Implementation sequence after design approval

B2 is design only. If approved, implement in small independently testable slices:
1. Data contract and deterministic candle/time tests.
2. Scale-safe structure/location features and Spot universe eligibility.
3. Regime classifier with explicit transition/shock behavior.
4. Independent continuation and pullback hypotheses.
5. Reversal and range-rejection hypotheses.
6. Decision arbitration, reason codes and separate score fields.
7. Flexible horizon evaluator.
8. Leakage-safe historical harness and reports.
9. Side-by-side shadow mode against 1.5.2.
10. Only after validation, consider UI integration and a separately signed candidate APK.

Each slice must be committed on an isolated branch with tests. No changes to the installed 1.5.2 APK, app data, signing configuration or release workflow. No PR merge or APK release without explicit user approval.

## 12. Open research decisions (must be measured, not guessed)

The following values must be selected from training/validation experiments and then frozen:
- ADX / trend-strength boundaries;
- ATR expansion shock threshold and normalization period;
- EMA slope lookback;
- range efficiency / overlap boundaries;
- ATR-relative setup location bands;
- candle body/wick quality thresholds;
- relative-volume baseline and minimum participation;
- per-regime/per-setup evidence thresholds;
- minimum historical sample size per horizon;
- minimum acceptable coverage and practical accuracy improvement.

Do not hard-code attractive-looking thresholds merely to make the engine produce more signals. The objective is robust out-of-sample directional accuracy with honest WAIT behavior.

---
**Stage B2 completion definition:** this document is an implementation-ready design contract, not proof of implementation or performance. Stage B3 begins only after this design is reviewed and the user authorizes source-code implementation.
