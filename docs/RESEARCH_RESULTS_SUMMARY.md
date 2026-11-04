# Prediction Lab — Consolidated Research Results

**Status:** research findings frozen; no algorithm candidate approved; no production code changed by this report.  
**Scope:** experimental research harness for BTC Structure Analyzer / Prediction Lab.  
**Branch:** `experiment/prediction-lab-research-harness`  
**Purpose:** preserve the results already generated so the team does not repeat audits or spend CI quota re-running the same work.

> **Important release warning:** The final holdout was evaluated once using the default configuration and did not demonstrate a reliable predictive edge. It is report-only and must not be used for tuning, candidate selection, or repeated evaluation. This report does not certify the algorithm for production or authorize an APK release.

## 1. Executive conclusion

1. The default baseline did not show stable performance across horizons. The validation results did not persist on the one-time final holdout.
2. No tested threshold or setup candidate improved all horizons consistently and survived validation. No candidate is approved.
3. Diagnostics identified plausible areas for future investigation (especially trend continuation confirmation and pullback definition), but the evidence is descriptive and sample sizes are small.
4. No algorithm thresholds, production behavior, B3 implementation, PR #9, or installed APK 1.5.2 were changed as part of this research.
5. Do not rerun the same historical dataset or reuse the final holdout. If research resumes, use a newly collected, strictly separated dataset and a predeclared test plan.

## 2. Dataset and methodology

- Historical window: 180 days.
- Assets: BTC, ETH, ZEC, SOL, XRP, NEAR, BNB, PEPE, UNI, TRX.
- Bars per coin: 5m = 51,839; 15m = 17,279; 1h = 4,319.
- Evaluation split: development, validation, and final holdout. The final holdout was evaluated once with the default configuration.
- The `trainFraction=0.60` setting only selects the evaluation start point. **No machine-learning model was trained.**
- The evaluated universe was selected using current 24-hour volume. This introduces survivorship/universe-selection bias: results are conditional on the currently selected universe, not a point-in-time historical universe.
- Returns are close-to-close directional returns. They do not model fees, slippage, spread, execution latency, order fills, intrabar path, or live risk.
- Hit rate is not the probability of a profitable live trade and is not evidence of a guaranteed win rate or daily profit.
- Signal counts, especially at 3h and 6h, are small. Percentages from small samples are highly uncertain.

## 3. Official non-overlap baseline results

These are the primary baseline metrics. Do not mix them with the supplementary diagnostic samples in later sections.

### Development split

| Horizon | Candidates | Signals | Hits | Misses | Neutral | Hit rate | Mean signed return |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1h | 25,870 | 50 | 21 | 27 | 2 | 42.00% | -0.25642% |
| 3h | 8,620 | 18 | 9 | 9 | 0 | 50.00% | +0.71249% |
| 6h | 4,310 | 7 | 1 | 6 | 0 | 14.29% | -0.14727% |

### Validation split

| Horizon | Candidates | Signals | Hits | Misses | Hit rate | Mean signed return |
|---|---:|---:|---:|---:|---:|---:|
| 1h | 8,630 | 13 | 9 | 4 | 69.23% | +0.75420% |
| 3h | 2,870 | 4 | 2 | 2 | 50.00% | -0.28542% |
| 6h | 1,430 | 3 | 2 | 1 | 66.67% | +0.42932% |

### Final holdout — one-time, default configuration only

| Horizon | Candidates | Signals | Hits | Misses | Hit rate | Mean signed return | Coverage |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1h | 8,630 | 14 | 7 | 7 | 50.00% | -0.12901% | 0.1622% |
| 3h | 2,870 | 5 | 2 | 3 | 40.00% | -0.51700% | 0.1742% |
| 6h | 1,430 | 4 | 0 | 4 | 0.00% | -0.97885% | 0.2797% |

**Interpretation:** validation performance did not persist on the final holdout. The 6h holdout result is 0/4, which is a warning signal but does not establish that the true hit rate is zero. Overall, these results do not demonstrate a reliable predictive edge. The very low coverage also means the system produced few signals relative to eligible candidates.

## 4. Candidate experiments — development and validation only

### One-factor development comparison

| Profile | 1h hit rate (signals) | 3h hit rate (signals) | 6h hit rate (signals) |
|---|---:|---:|---:|
| Default | 42.00% (50) | 50.00% (18) | 14.29% (7) |
| lookback_16 | 40.38% (52) | 57.89% (19) | 28.57% (7) |
| tolerance_035 | 47.76% (67) | 45.45% (22) | 25.00% (8) |
| wick_090 | 41.82% (55) | 47.62% (21) | 12.50% (8) |
| volume_060 | 42.31% (52) | 47.37% (19) | 12.50% (8) |

No profile was consistently better across all three horizons. The 6h samples are particularly small. No profile is approved.

### lookback_16 validation check

| Profile | 1h | 3h | 6h |
|---|---:|---:|---:|
| Default | 9/13 (69.23%) | 2/4 (50.00%) | 2/3 (66.67%) |
| lookback_16 | 10/16 (62.50%) | 2/6 (33.33%) | 2/4 (50.00%) |

Mean signed returns for lookback_16 validation were +0.52741% (1h), -0.91821% (3h), and +0.14430% (6h). It did not beat default consistently and was rejected.

### tolerance_035 — earlier isolated comparison

An earlier sample using a different sampling/metric configuration reported:

| Horizon | Default | tolerance_035 |
|---|---:|---:|
| 1h | 21/50 (42.00%), mean -0.2564% | 32/67 (47.76%), mean -0.1413% |
| 3h | 5/11 (45.45%), mean +0.6926% | 6/16 (37.50%), mean +0.3934% |
| 6h | 2/6 (33.33%), mean -0.01882% | 3/9 (33.33%), mean -0.11141% |

This is a separate sampling result and must not be combined with the official non-overlap table. It also does not support a global change.

## 5. B3 implementation audit — observed configuration

The following values were read from the B3 implementation during audit. This section documents existing behavior; it does not change or approve it.

### AdaptivePredictionEngine defaults

- Minimum bars: 50.
- ATR period: 14; ATR baseline: 30.
- Maximum staleness: 2 intervals.
- Shock expansion: 1.8.
- Trend efficiency threshold: 0.38.
- Trend slope threshold: 1.25 ATR.
- Minimum directional-bias efficiency: 0.25.
- Minimum directional-bias slope: 0.25 ATR.
- Regime classification: SHOCK when expansion >= 1.8; TREND when efficiency >= 0.38 and absolute slope >= 1.25 ATR; RANGE when efficiency <= 0.22; otherwise TRANSITION.
- TRANSITION and UNKNOWN return WAIT.
- Directional bias requires efficiency >= 0.25 and absolute slope >= 0.25 ATR.
- Trend setup requires 1h/15m bias alignment, a 15m breakout or pullback pattern, and a 5m trigger/body/volume confirmation.
- Range setup requires a 15m sweep/reclaim/wick/body/volume pattern plus a 5m trigger/body confirmation.

### Other detectors

- RangeReversalDetector: boundary lookback 24; boundary ATR tolerance 0.20; minimum wick/body ratio 1.25; minimum volume ratio 0.75; minimum body 0.10 ATR.
- TrendSetupDetector: ATR 14; EMA 20; breakout lookback 10; pullback maximum EMA distance 0.65 ATR; minimum body 0.20 ATR; minimum volume ratio 0.80.
- Pullback definition was judged permissive: proximity to EMA20 plus a directional candle and body >= 0.20 ATR can qualify without clear evidence of an actual retracement.

## 6. Diagnostic audits — supplementary samples

These are diagnostic or overlapping samples, not the official non-overlap baseline. Treat them as exploratory evidence.

### 6.1 Regime distribution and transition behavior

Full 5m development sampling: 305,020 decisions.

| Regime | Decisions |
|---|---:|
| RANGE | 221,190 |
| TRANSITION | 59,866 |
| TREND | 12,012 |
| SHOCK | 11,952 |

- All TRANSITION cases had efficiency between 0.22 and 0.38; none were blocked by the slope condition.
- 15m trend bias was WAIT in 6,534 of 12,012 TREND-regime decisions (54.4%). Most observed alignment friction was a WAIT state, not an opposite directional bias.
- A direction-only diagnostic lowering the ER threshold from 0.38 to 0.30 increased candidate count but worsened hit rate/signed returns at 1h and 3h; 6h was mixed. Lowering the threshold globally was rejected.

### 6.2 Range reversal funnel and 5m confirmation

- 73,316 unique 15m candles were classified RANGE.
- 61,586 (~84.0%) had NO_BOUNDARY_SWEEP.
- Too-deep sweep: 4,764 support and 4,459 resistance cases.
- Wick/body too low: 626 support and 608 resistance cases.
- 467 passed boundary/wick/body/volume checks.
- Additional failures: body too small 292; failed reclaim 188 support and 196 resistance; low volume 124; both boundaries swept 6.
- 5m confirmation: same-candle pass 81; next-candle pass 122; union 174 with overlap 29; next-only 93.

Exploratory outcomes for those confirmation subsets:

| Confirmation subset | 1h | 3h | 6h |
|---|---:|---:|---:|
| Same-bar | 50.6% hit, mean +0.071% | 54.3%, +0.268% | 53.1%, +0.183% |
| Next-only | 55.6% hit, mean -0.073% | 51.1%, -0.042% | 44.4%, -0.152% |

These are subset diagnostics and not an independently validated candidate. They do not authorize changing confirmation timing.

### 6.3 Pullback strictness candidate

A candidate requiring at least 0.15 ATR countertrend movement across the previous three 15m candles was tested diagnostically.

- Raw B3 trend signals: 281 total = 260 breakout and 21 pullback.
- Candidate retained only 3 pullback signals.
- B3 aggregate hit rates: 1h 45.59%, 3h 50.00%, 6h 54.12%.
- Candidate aggregate hit rates: 1h 46.40%, 3h 47.00%, 6h 52.44%.

There was no consistent improvement; reject the candidate.

### 6.4 Extra range rejection candle-direction condition

- Initial range candidates: 467.
- Candidates after extra condition: 238 before 5m confirmation.
- Final confirmed signals remained exactly 80.
- Outcomes: 1h 50.00% hit, mean +0.0699%; 3h 54.43%, +0.2627%; 6h 53.25%, +0.1886%.

The extra condition did not improve final signal quality or change the final signal count. No change recommended.

### 6.5 Bias threshold isolation

The Kotlin config parameter `minimumBiasEfficiency` is shared by 1h and 15m bias. Changing it in the engine changes both timeframes, so the audit used offline isolated comparisons.

- Baseline: 1h 0.25 / 15m 0.25.
- Candidate A: 1h 0.25 / 15m 0.20.
- Candidate B: 1h 0.20 / 15m 0.25.

Candidate A:
- 1h: 61 signals, 31 hits, 30 misses, 50.82%, mean -0.1856%.
- 3h: 21 signals, 11 hits, 10 misses, 52.38%, mean +0.6043%.
- 6h unchanged from baseline: 7 signals, 1 hit, 6 misses, 14.29%, mean -0.1473%.
- Validation was unchanged versus baseline.

Candidate B produced no changes because the TREND regime gate already requires 1h ER >= 0.38. Neither candidate was adopted.

## 7. Signal family, coin, volatility and trigger diagnostics

### 7.1 Signal family

| Horizon | Trend family | Range reversal |
|---|---:|---:|
| 1h | 13/35 (37.14%) | 8/15 (53.33%) |
| 3h | 6/9 (66.67%) | 3/9 (33.33%) |
| 6h | 0/3 | 1/4 |

At 1h, continuation was 13/34, pullback 0/1, and range reversal 8/15. At 3h, continuation was 6/9 and range reversal 3/9. At 6h, continuation was 0/3 and range reversal 1/4. Small sample sizes prevent strong conclusions.

### 7.2 Per-coin 1h results

| Coin | Hits / signals | Hit rate |
|---|---:|---:|
| BTC | 3/7 | 42.86% |
| ETH | 3/4 | 75.00% |
| ZEC | 2/3 | 66.67% |
| SOL | 2/7 | 28.57% |
| XRP | 3/6 | 50.00% |
| NEAR | 1/7 | 14.29% |
| BNB | 1/4 | 25.00% |
| PEPE | 0/3 | 0.00% |
| UNI | 2/4 | 50.00% |
| TRX | 4/5 | 80.00% |

These are very small per-coin samples and must not be used to permanently include/exclude assets.

### 7.3 Volatility bins — 1h

| Volatility bin | Hits / signals |
|---|---:|
| < 0.8 | 2/5 |
| 0.8–1.2 | 9/24 |
| 1.2–1.8 | 10/21 |

No robust volatility filter is justified by these counts.

### 7.4 Prior 3h movement

- Same-direction raw prior 3h move > 2%: 23 signals, 7 hits (30.4%), mean signed return -0.579%.
- ATR-normalized prior movement > 2 ATR: 23 signals, 9 hits (39.13%), mean -0.421%.
- <= 2 ATR: 27 signals, 12 hits (44.44%), mean -0.116%.

No filter was justified.

### 7.5 MFE / MAE

- Hits (n=21): mean MFE +1.217%, mean MAE -0.472%.
- Misses + neutral (n=29): mean MFE +0.311%, mean MAE -1.718%.

This suggests false signals often moved against the forecast with limited favorable excursion in this sample. It does not prove causation or establish a live exit rule.

### 7.6 5m trigger quality

Trend continuation 1h signals:
- n=34; hit rate 13/34 = 38.24%.
- Median 5m volume ratio: hits 2.51x, misses 1.03x.
- Excluding TRX: n=30, 9 hits, 30.00%; volume medians remained 2.51x for correct versus 1.01x for incorrect outcomes.
- Median body/ATR: hits 0.659 versus misses 0.840.
- Median breakout distance/ATR: hits 0.395 versus misses 0.536. Larger candles and farther breakouts were not automatically better.

Range reversal:
- 1h n=15, hit rate 8/15 = 53.33%.
- Median distance/ATR: hits 0.118 versus misses 0.392.

Volume observations are descriptive only. The samples are small; no volume/body/distance filter was approved.

## 8. Decisions and rejected changes

| Proposed change | Evidence | Decision |
|---|---|---|
| Change range lookback to 16 | Development mixed; validation worse than default overall | Reject |
| Apply tolerance_035 globally | 1h improved in one sample, 3h worsened, 6h inconsistent | Reject |
| Change wick threshold to 0.90 | No consistent multi-horizon improvement | Reject |
| Lower volume threshold to 0.60 | No consistent multi-horizon improvement | Reject |
| Lower ER trend threshold 0.38 to 0.30 | More candidates but weaker 1h/3h direction-only results | Reject |
| Tighten pullback with 0.15 ATR retracement | Only 3 of 21 pullbacks retained; aggregate not consistently improved | Reject |
| Add extra range candle-direction condition | Final confirmed signal count and quality unchanged | Reject |
| Lower 15m bias efficiency to 0.20 | Development mixed; validation unchanged | Reject |
| Lower 1h bias efficiency to 0.20 | No effect due to existing TREND regime gate | No benefit |

**Final research decision:** no candidate is approved for production. Keep existing production behavior unchanged until new independent evidence supports a specific, predeclared candidate.

## 9. Data and experiment limitations

- Current-volume universe selection creates survivorship and selection bias.
- Small signal counts make hit-rate percentages unstable.
- Close-to-close scoring omits transaction costs, spreads, slippage, fill probability, and intrabar path.
- Diagnostic subsets may overlap or use different sampling rules; they are not interchangeable with the official non-overlap baseline.
- Development experiments are exploratory and may be affected by multiple comparisons.
- The final holdout has already been viewed. It is no longer an untouched holdout and must remain report-only.
- No evidence here supports a guaranteed 80% win rate, stable daily profit, or production readiness.

## 10. Safe next steps toward an APK

1. Preserve this report and the existing research harness on the experiment branch.
2. Do not run another CI workflow merely to repeat completed audits. The research workflows are manual-only to conserve quota.
3. If another research round is approved, define one candidate and acceptance criteria before collecting a **new** dataset. Keep that dataset separate from all prior development/validation/holdout data.
4. First establish whether the existing APK 1.5.2 is intended to remain the APK baseline or whether a new APK is requested. Do not overwrite or uninstall the existing installed app.
5. Only after the user explicitly approves a specific algorithm change should production implementation be modified. Then run one consolidated validation, inspect the result once, and only if it passes build a versioned APK. Do not merge PR #10 or modify B3/PR #9 as part of this documentation step.

## 11. Repository safety state

At the time this report was prepared:
- Research branch: `experiment/prediction-lab-research-harness`.
- PR #10: open draft; experimental only; not merged.
- PR #9: untouched.
- B3 implementation branch: untouched.
- Installed APK 1.5.2: untouched.
- No APK build or release was performed.
- No new CI run was started for this report.
