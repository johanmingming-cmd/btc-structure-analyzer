#!/usr/bin/env python3
"""Download a pinned, gap-free Binance Spot dataset for legacy Scanner replay.

Unlike the Adaptive research downloader, this requires an explicit symbol universe
and includes 1m candles for the live Scanner journal's outcome timestamps.
"""
import csv
import json
import math
import os
import shutil
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

BASE = "https://data-api.binance.vision/api/v3"
INTERVALS = {"1m": 60_000, "5m": 300_000, "15m": 900_000, "1h": 3_600_000}
DAYS = int(os.environ.get("LEGACY_HISTORY_DAYS", "180"))
SYMBOLS_RAW = os.environ.get("LEGACY_HISTORY_SYMBOLS", "")
OUT = Path(os.environ.get("LEGACY_HISTORY_DIR", "build/legacy-scanner-history"))
LIMIT = 1000


def parse_symbols(raw):
    symbols = [item.strip().upper() for item in raw.split(",") if item.strip()]
    if len(symbols) < 10:
        raise ValueError("LEGACY_HISTORY_SYMBOLS must contain at least 10 pinned USDT symbols")
    if len(symbols) != len(set(symbols)):
        raise ValueError("LEGACY_HISTORY_SYMBOLS must not contain duplicates")
    if any(not symbol.endswith("USDT") or not symbol[:-4].isalnum() for symbol in symbols):
        raise ValueError("Every pinned symbol must be a valid-looking USDT Spot symbol")
    return symbols


def get_json(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Legacy-Scanner-Historical-Replay/1.0"})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            if error.code not in (418, 429, 500, 502, 503, 504) or attempt == 4:
                raise
            time.sleep(2 * (attempt + 1))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            if attempt == 4:
                raise
            time.sleep(1.5 * (attempt + 1))


def validate_kline(row, symbol, interval):
    step = INTERVALS[interval]
    if not isinstance(row, (list, tuple)) or len(row) < 7:
        raise ValueError(f"Malformed kline row: {symbol} {interval}")
    try:
        open_time = int(row[0])
        open_price, high, low, close, volume = map(float, row[1:6])
        close_time = int(row[6])
    except (ValueError, TypeError, OverflowError) as error:
        raise ValueError(f"Non-numeric kline field: {symbol} {interval}") from error
    values = (open_price, high, low, close, volume)
    if open_time < 0 or open_time % step != 0 or close_time != open_time + step - 1:
        raise ValueError(f"Invalid candle timestamps: {symbol} {interval} {open_time}")
    if not all(math.isfinite(value) for value in values):
        raise ValueError(f"Non-finite OHLCV: {symbol} {interval} {open_time}")
    if min(open_price, high, low, close) <= 0 or volume < 0:
        raise ValueError(f"Non-positive price or negative volume: {symbol} {interval} {open_time}")
    if high < max(open_price, close, low) or low > min(open_price, close, high):
        raise ValueError(f"Invalid OHLC range: {symbol} {interval} {open_time}")
    return open_time, close_time


def download(symbol, interval, start, end):
    step = INTERVALS[interval]
    rows_by_open = {}
    cursor = start
    while cursor < end:
        query = urllib.parse.urlencode({
            "symbol": symbol, "interval": interval,
            "startTime": cursor, "endTime": end - 1, "limit": LIMIT,
        })
        batch = get_json(BASE + "/klines?" + query)
        if not isinstance(batch, list):
            raise RuntimeError(f"Unexpected kline response: {symbol} {interval}")
        if not batch:
            break
        for row in batch:
            open_time, close_time = validate_kline(row, symbol, interval)
            if open_time < start or close_time >= end:
                continue
            previous = rows_by_open.get(open_time)
            if previous is not None and previous != row:
                raise RuntimeError(f"Conflicting duplicate candle: {symbol} {interval} {open_time}")
            rows_by_open[open_time] = row
        next_cursor = int(batch[-1][0]) + step
        if next_cursor <= cursor:
            raise RuntimeError(f"Pagination stalled: {symbol} {interval}")
        cursor = next_cursor
        time.sleep(0.08)

    ordered = [rows_by_open[key] for key in sorted(rows_by_open)]
    expected = list(range(start, end, step))
    actual = [int(row[0]) for row in ordered]
    if actual != expected:
        missing = sorted(set(expected) - set(actual))
        extra = sorted(set(actual) - set(expected))
        raise RuntimeError(
            f"Incomplete {symbol} {interval}: expected {len(expected)}, got {len(actual)}; "
            f"missing={missing[:3]}, extra={extra[:3]}"
        )
    return ordered


def write_symbol(root, symbol, datasets):
    temporary = root / (symbol + ".tmp")
    if temporary.exists():
        shutil.rmtree(temporary)
    temporary.mkdir(parents=True)
    for interval, rows in datasets.items():
        with (temporary / f"{interval}.csv").open("w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle)
            writer.writerow(["openTime", "open", "high", "low", "close", "volume", "closeTime"])
            for row in rows:
                writer.writerow(row[:7])
    final = root / symbol
    if final.exists():
        shutil.rmtree(final)
    temporary.replace(final)


def main():
    if DAYS < 30:
        raise ValueError("LEGACY_HISTORY_DAYS must be at least 30")
    symbols = parse_symbols(SYMBOLS_RAW)
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    requested_end = int(os.environ.get("LEGACY_HISTORY_END_TIME_MS", "0") or 0) or now_ms
    # Align end to an hour boundary so every interval has exact common boundaries.
    end = requested_end // INTERVALS["1h"] * INTERVALS["1h"]
    start = end - DAYS * 86_400_000
    if start < 0:
        raise ValueError("Resolved history start time is invalid")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    staging = OUT.with_name(OUT.name + ".staging")
    backup = OUT.with_name(OUT.name + ".previous")
    if not OUT.exists() and backup.exists():
        backup.replace(OUT)
    elif OUT.exists() and backup.exists():
        shutil.rmtree(backup)
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    try:
        coverage = {}
        for index, symbol in enumerate(symbols, start=1):
            print(f"[{index}/{len(symbols)}] Downloading pinned universe: {symbol}", flush=True)
            datasets = {}
            for interval in INTERVALS:
                datasets[interval] = download(symbol, interval, start, end)
                print(f"  {interval}: {len(datasets[interval])} closed candles", flush=True)
            write_symbol(staging, symbol, datasets)
            coverage[symbol] = {
                interval: {
                    "bars": len(rows),
                    "firstOpenTimeMs": int(rows[0][0]),
                    "lastCloseTimeMs": int(rows[-1][6]),
                }
                for interval, rows in datasets.items()
            }

        manifest = {
            "schemaVersion": 1,
            "source": "Binance Spot public klines",
            "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
            "startTimeMs": start,
            "endTimeMsExclusive": end,
            "requestedDays": DAYS,
            "universeSelection": "explicit pinned LEGACY_HISTORY_SYMBOLS; not selected from current volume",
            "symbols": symbols,
            "intervals": list(INTERVALS),
            "coverage": coverage,
            "limitations": [
                "Historical bid/ask spread is not present in OHLCV.",
                "Point-in-time Binance USD-M Futures eligibility is not present in Spot klines.",
                "Rolling quote volume derived from OHLCV is an approximation, not the historical Binance ticker field.",
            ],
        }
        (staging / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        if backup.exists():
            shutil.rmtree(backup)
        had_previous = OUT.exists()
        if had_previous:
            OUT.replace(backup)
        try:
            staging.replace(OUT)
        except Exception:
            if had_previous and backup.exists() and not OUT.exists():
                backup.replace(OUT)
            raise
        if backup.exists():
            shutil.rmtree(backup)
        print("LEGACY_HISTORY_READY " + ", ".join(symbols), flush=True)
        print(f"Dataset: {OUT} | {DAYS} days | 1m, 5m, 15m, 1h", flush=True)
    except Exception:
        if staging.exists():
            shutil.rmtree(staging)
        raise


if __name__ == "__main__":
    main()
