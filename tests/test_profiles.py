#!/usr/bin/env python3
from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class ProfileTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.data = json.loads((ROOT / "profiles/profiles.json").read_text())
        cls.profiles = {item["id"]: item for item in cls.data["profiles"]}

    def test_unique_ids_and_required_fields(self) -> None:
        self.assertEqual(len(self.profiles), len(self.data["profiles"]))
        for profile in self.data["profiles"]:
            for key in ("id", "format", "width", "height", "fps", "stable", "experimental"):
                self.assertIn(key, profile)

    def test_raw_high_rate_yuy2_is_not_advertised(self) -> None:
        forbidden = {"yuy2_1920x1080_60", "yuy2_3840x2160_15", "yuy2_3840x2160_30", "yuy2_3840x2160_60"}
        self.assertTrue(forbidden.isdisjoint(self.profiles))

    def test_bandwidth_math_matches_usb2_boundary(self) -> None:
        yuy2_1080p60 = 1920 * 1080 * 2 * 60 * 8
        yuy2_4k60 = 3840 * 2160 * 2 * 60 * 8
        budget = self.data["usb"]["high_speed_theoretical_bps"]
        self.assertGreater(yuy2_1080p60, budget)
        self.assertGreater(yuy2_4k60, budget)

    def test_experimental_profiles_are_not_stable(self) -> None:
        self.assertFalse(self.profiles["mjpeg_1920x1080_60"]["stable"])
        self.assertFalse(self.profiles["h264_3840x2160_60"]["stable"])


if __name__ == "__main__":
    unittest.main()
