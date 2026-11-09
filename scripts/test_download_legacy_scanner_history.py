import unittest

from scripts.download_legacy_scanner_history import parse_symbols, validate_kline


class LegacyScannerHistoryDownloaderTest(unittest.TestCase):
    def test_requires_explicit_pinned_universe_of_at_least_ten(self):
        with self.assertRaises(ValueError):
            parse_symbols("BTCUSDT,ETHUSDT")

    def test_rejects_duplicate_symbols(self):
        symbols = ",".join(["BTCUSDT"] * 10)
        with self.assertRaises(ValueError):
            parse_symbols(symbols)

    def test_accepts_ten_unique_usdt_symbols(self):
        symbols = "BTCUSDT,ETHUSDT,BNBUSDT,SOLUSDT,XRPUSDT,ADAUSDT,DOGEUSDT,TRXUSDT,LINKUSDT,LTCUSDT"
        self.assertEqual(len(parse_symbols(symbols)), 10)

    def test_validates_aligned_complete_one_minute_candle(self):
        row = [0, "100", "102", "99", "101", "12.5", 59_999]
        self.assertEqual(validate_kline(row, "BTCUSDT", "1m"), (0, 59_999))

    def test_rejects_misaligned_or_incomplete_candle(self):
        row = [1, "100", "102", "99", "101", "12.5", 60_000]
        with self.assertRaises(ValueError):
            validate_kline(row, "BTCUSDT", "1m")

    def test_rejects_invalid_ohlc(self):
        row = [0, "100", "98", "99", "101", "12.5", 59_999]
        with self.assertRaises(ValueError):
            validate_kline(row, "BTCUSDT", "1m")


if __name__ == "__main__":
    unittest.main()
