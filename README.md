# BTC Structure Analyzer

Android app for BTC/USDT market-structure analysis with multi-timeframe signals and a local signal journal.

## Current development status

The `fix/historical-spot-evaluation` branch contains ongoing work on historical signal evaluation and export quality. GitHub Actions has successfully compiled the debug APK and run unit tests for the current changes. This does not establish predictive accuracy or replace on-device testing.

## Historical evaluation safeguards

- Existing journal records without cohort metadata are read as `LEGACY`.
- Newly recorded signals are tagged `PROSPECTIVE_V1` so new observations can be analyzed separately.
- Signal recording deduplicates repeated candidates within a scan.
- Historical trade-outcome evaluation rejects malformed or incomplete one-minute candles and treats missing coverage as insufficient data rather than assuming a result.
- If TP and SL are both touched within the same post-entry candle and intrabar order is unavailable, the outcome is marked ambiguous.
- Export creates a ZIP containing `signals.csv`, `evaluations.csv`, `signal-analysis.json`, and a data dictionary. Export is read-only and does not modify the local journal.

## Interpreting exported data

Use the `cohort` field to separate `PROSPECTIVE_V1` observations from `LEGACY` records. Check each row's `data_quality`, `price_source`, and evaluation timestamp before including it in any analysis. A directional move in favor of a signal is not the same as a profitable trade. The export does not include the full historical OHLC series needed to independently establish TP/SL hit order.

Do not treat signals or current test results as validated trading predictions. Accuracy should only be assessed after enough valid prospective observations have matured across the relevant 15/30/60-minute horizons.

## Build

Open the project in Android Studio and allow Gradle sync. A compatible Android SDK and network access for dependency resolution are required. The GitHub Actions workflow on the development branch runs a debug build and unit tests.
