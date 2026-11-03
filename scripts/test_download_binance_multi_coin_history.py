import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("download_binance_multi_coin_history.py")
SPEC = importlib.util.spec_from_file_location("history_downloader", MODULE_PATH)
DOWNLOADER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DOWNLOADER)


class KlineValidationTests(unittest.TestCase):
    def setUp(self):
        self.valid = [0, "100.0", "105.0", "95.0", "102.0", "12.5", 299999]

    def test_accepts_valid_closed_candle(self):
        self.assertEqual(DOWNLOADER.validate_kline(self.valid, "BTCUSDT", "5m"), (0, 299999))

    def test_rejects_short_row(self):
        with self.assertRaises(ValueError):
            DOWNLOADER.validate_kline(self.valid[:5], "BTCUSDT", "5m")

    def test_rejects_non_finite_ohlcv(self):
        row = self.valid.copy()
        row[2] = "NaN"
        with self.assertRaises(ValueError):
            DOWNLOADER.validate_kline(row, "BTCUSDT", "5m")

    def test_rejects_inverted_ohlc_range(self):
        row = self.valid.copy()
        row[2] = "99"
        with self.assertRaises(ValueError):
            DOWNLOADER.validate_kline(row, "BTCUSDT", "5m")

    def test_rejects_wrong_candle_duration(self):
        row = self.valid.copy()
        row[6] = 300000
        with self.assertRaises(ValueError):
            DOWNLOADER.validate_kline(row, "BTCUSDT", "5m")

    def test_rejects_negative_volume(self):
        row = self.valid.copy()
        row[5] = "-1"
        with self.assertRaises(ValueError):
            DOWNLOADER.validate_kline(row, "BTCUSDT", "5m")


if __name__ == "__main__":
    unittest.main()
