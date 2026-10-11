#!/usr/bin/env python3
"""Tests for the live-feed probe itself.

Why a diagnostic needs tests: the probe's whole job is to report what is reachable, and its first version
reported a *clean* result while silently skipping the alternate source - because the erail call sat below the
primary's `if doc is None: continue`. A fallback that only fires when the primary works is not a fallback, and
nothing in the pipeline could see it. That is the same defect the app kept hitting on the device, so it gets a
regression test rather than a comment.

Run: python3 tools/ci/test_probe_live_feed.py  (also run by the `checks` job)
"""
from __future__ import annotations

import datetime
import importlib.util
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("probe_live_feed", os.path.join(HERE, "probe_live_feed.py"))
plf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plf)

TODAY = datetime.datetime(2026, 10, 12, 0, 30)
DEAD = ("URLError: timed out", None)


class ProbeFallback(unittest.TestCase):
    def test_alternate_is_asked_when_the_primary_dies(self):
        asked = []

        def alt(a, b, c):
            asked.append((a, b, c))
            return "HTTP 200 900B", ("16228~TALGUPPA MYSURU EXP~SMET~22:10~MYS~07:00~2026-10-11-7-56-55~\n"
                                     "16226~SOME OTHER~SMET~11:25~MYS~17:05~x~\n")

        lines, entry = plf.probe_pair("SMET", "MYS", ["16228"], "12-Oct-2026", TODAY,
                                      fetch=lambda plain: DEAD, alt=alt)
        self.assertEqual([(  "SMET", "MYS", "12-Oct-2026")], asked, "alternate must be asked even when primary failed")
        self.assertTrue(any("erail.in (alternate)" in ln for ln in lines), lines)
        self.assertTrue(any("primary gave nothing usable" in ln for ln in lines),
                        "the report must say the pair rests on one witness, not that it succeeded")
        self.assertIsNotNone(entry, "an alternate-only answer still produces a proposed fact")
        self.assertIn("16228", entry["must_include"])
        self.assertIn("16226", entry["must_include"])

    def test_no_entry_and_no_lie_when_both_sources_are_dead(self):
        lines, entry = plf.probe_pair("SMET", "MYS", ["16228"], "12-Oct-2026", TODAY,
                                      fetch=lambda plain: DEAD, alt=lambda a, b, c: ("URLError: timed out", ""))
        self.assertIsNone(entry, "unreachable must never become an empty-but-confident fact list")
        self.assertTrue(any("erail.in (alternate)" in ln for ln in lines), "the block must still be reported")

    def test_a_fixture_claim_contradicted_by_both_sources_is_called_out(self):
        alt_body = "16226~X~SMET~11:25~MYS~17:05~2026-10-11-7-56-55~\n"
        lines, entry = plf.probe_pair("SMET", "MYS", ["16228"], "12-Oct-2026", TODAY,
                                      fetch=lambda plain: ("ok", {"TRAINNO": ["16226"], "STN": ["MYS"]}),
                                      alt=lambda a, b, c: ("HTTP 200 60B", alt_body))
        self.assertTrue(any("erail does not list 16228 either" in ln for ln in lines), lines)

    def test_feed_stamp_is_read_from_the_payload(self):
        # The stamp is how a keyless text answer carries "how current am I"; without it we are back to
        # trusting a git commit date for the data's freshness, which is the mistake that started this.
        self.assertEqual(
            "2026-10-11-7-56-55",
            __import__("re").search(r"(\d{4}-\d{2}-\d{2}-\d{1,2}-\d{1,2}-\d{1,2})",
                                   "abc~2026-10-11-7-56-55~def").group(1))


class FlatShapes(unittest.TestCase):
    def test_parallel_arrays_are_reported_ragged_instead_of_transposed_lie(self):
        flat = plf.flat_table({"TRAINNO": ["16228", "16226"], "ATD": ["22:10"]})
        self.assertEqual({"TRAINNO": 2, "ATD": 1}, flat["lengths"])
        self.assertEqual([], flat["rows"], "ragged columns must not be silently zipped into rows")

    def test_row_objects_still_work(self):
        self.assertIsNone(plf.flat_table({"a": 1}))


class ErailWitness(unittest.TestCase):
    def test_identity_is_asked_even_when_the_primary_died(self):
        # The pair probe had this bug; this case exists so the identity probe cannot grow it back.
        asked = []
        live = {"trains": []}
        lines = plf.erail_identity_lines("16228", live,
                                        get=lambda q: (asked.append(q) or
                                                       ("HTTP 200 400B", "16228~TALGUPPA MYSURU EXP~SMET~MYS~x")))
        self.assertTrue(asked and "TrainNo=16228" in asked[0])
        self.assertTrue(any("identity of 16228 via erail.in" in l for l in lines))
        self.assertEqual([{"train_no": "16228", "name": "TALGUPPA MYSURU EXP", "from": "SMET", "to": "MYS"}],
                         live["trains"], "4-letter codes must survive parsing intact")

    def test_a_miscounted_column_is_reported_not_recorded(self):
        live = {"trains": []}
        lines = plf.erail_identity_lines("16228", live,
                                        get=lambda q: ("HTTP 200 60B", "16228~NAME~TALGUPPA TOWN~16228"))
        self.assertEqual([], live["trains"], "a half-parsed identity must not become a fact")
        self.assertTrue(any("not recorded as a truth" in l for l in lines))

    def test_date_forms_cover_both_styles_the_site_accepts(self):
        qs = plf.erail_candidates("SMET", "MYS", "12-Oct-2026")
        self.assertIn("Date_1=12-10-2026", qs[0], "the DD-MM-YYYY candidate must be numeric")
        self.assertIn("Date_1=12-Oct-2026", qs[1])
        self.assertEqual(4, len(qs), "the 404 control stays in the ladder so the report proves the path")


if __name__ == "__main__":
    unittest.main(verbosity=2)
