# Prediction Lab Research Harness (Experimental)

## Scope and safety boundary

This branch is isolated from the installed 1.5.2 APK and the existing B3 implementation branch. It is research tooling only. It does not change live scanner behavior, UI, production defaults, or release artifacts. No branch merge or release is authorized by this work.

The current research phase is frozen. Do not add more exploratory parameter sweeps or repeat completed audits. No algorithm threshold has been approved for production because none has shown a consistent improvement across development and validation.

## Completed research infrastructure

- [x] Create isolated experiment branch from B3 head.
- [x] Add detector-level WAIT sub-reason diagnostics without changing default thresholds.
- [x] Add focused unit tests for diagnostic paths.
- [x] Add fixed-universe and fixed-end downloader controls.
- [x] Record exact candle coverage in the manifest.
- [x] Add chronological segment evaluation with warm-up history and segment-contained outcomes.
- [x] Add development/validation/final-holdout separation.
- [x] Use non-overlapping outcome windows as the primary comparison.
- [x] Retain the exact downloaded dataset, manifest, and report as a workflow artifact.
- [x] Complete the initial 180-day, 10-symbol research run and baseline report.
- [x] Complete diagnostic audits of regime, setup family, coin, volatility, pullback, range rejection, and 5m trigger quality.

## Frozen findings

- The default baseline did not demonstrate a stable predictive edge.
- Validation results did not persist on the final holdout.
- Tested range-filter and bias-threshold candidates did not show a consistent improvement; none is approved for production.
- 5m trend volume showed a descriptive difference between correct and incorrect signals, but sample size and coin concentration are insufficient to justify a new filter.
- Pullback definition is permissive, but the tested stricter retracement candidate did not improve results consistently.
- The final holdout was evaluated once using the default configuration. It is report-only and must never be reused to tune, select, or validate new candidates.

## Dataset and interpretation limits

The initial 180-day universe was selected using current 24-hour quote volume. Results are conditional on that selected universe and have survivorship/universe-selection bias; they are not a point-in-time historical universe. The downloader supports pinned symbols and a fixed end timestamp for future reproducibility.

Close-to-close returns omit fees, slippage, execution, and path risk. Hit rate is not a win probability or evidence of live profitability. Small signal counts, especially at 6h, must be shown alongside every metric.

## One remaining execution

The research harness and audit phase are complete. Do not make speculative algorithm changes now.

When ready, run the historical workflow manually exactly once with a newly downloaded dataset and frozen code. It will run focused tests, perform the chronological report, and retain the dataset/manifest/report artifact. Do not rerun the same dataset after its final holdout has been evaluated. After that single run, collect the report and analyze it once as a consolidated result.

## CI and quota protection

Both research workflows are manual-dispatch only. They do not run automatically on push or pull-request events. Prefer the single historical-validation workflow for the final consolidated run; use the unit-only workflow only when a code change specifically requires a quick check.

The workflows do not build or sign an APK. No APK build, merge, release, or production change is part of this research phase.
