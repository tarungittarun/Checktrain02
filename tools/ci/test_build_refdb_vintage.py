#!/usr/bin/env python3
"""Regression tests for source-file vintage and explicit freshness metadata."""
from __future__ import annotations

import unittest

from tools import build_refdb as etl


class ReferenceDataVintageTest(unittest.TestCase):
    def setUp(self):
        self.provenance = {
            "Spin1234/IndianRailwayOpenReference.github.io": {
                "head_commit": "primary-head",
                "head_date": "2026-06-20",
                "history_complete": True,
                "files": {
                    "schedules.json": {"commit": "schedule-change", "date": "2026-06-14"},
                    "stations.json": {"commit": "station-change", "date": "2026-05-20"},
                    "trains.json": {"commit": "train-change", "date": "2026-06-14"},
                    "EXP-TRAINS.json": {"commit": "extra-change", "date": "2026-06-14"},
                },
            },
            "prasenjit-27/Indian-Railway-Data": {
                "head_commit": "run-days-head",
                "head_date": "2026-08-30",
                "history_complete": True,
                "files": {
                    "trains.json": {"commit": "run-days-change", "date": "2026-08-30"},
                },
            },
        }

    def test_schedule_vintage_uses_schedule_file_history_not_repo_head_or_overlay(self):
        self.assertEqual("2026-06-20", etl.source_head_vintage(self.provenance))
        self.assertEqual("2026-06-14", etl.timetable_vintage(self.provenance))
        self.assertEqual("2026-08-30", etl.file_vintage(
            self.provenance, etl.declared_paths_for_roles({"run_days"})))
        self.assertEqual("2026-05-20", etl.file_vintage(
            self.provenance, etl.declared_paths_for_roles({"primary"}) & {
                ("Spin1234/IndianRailwayOpenReference.github.io", "stations.json")
            }))

    def test_missing_history_for_any_schedule_input_makes_vintage_unknown(self):
        repo = "Spin1234/IndianRailwayOpenReference.github.io"
        incomplete = {**self.provenance, repo: {
            **self.provenance[repo],
            "files": {**self.provenance[repo]["files"],
                      "EXP-TRAINS.json": {"commit": "", "date": ""}},
        }}
        self.assertIsNone(etl.timetable_vintage(incomplete))

    def test_stale_schedule_status_is_measured_at_the_build_date(self):
        self.assertEqual({"as_of": "2026-06-14", "age_days": 120, "status": "stale"},
                         etl.freshness_snapshot("2026-06-14", "2026-10-12"))

    def test_unknown_and_future_source_dates_are_not_called_recent(self):
        self.assertEqual({"as_of": None, "age_days": None, "status": "unknown"},
                         etl.freshness_snapshot(None, "2026-10-12"))
        self.assertEqual({"as_of": "2026-10-13", "age_days": -1, "status": "future_dated"},
                         etl.freshness_snapshot("2026-10-13", "2026-10-12"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
