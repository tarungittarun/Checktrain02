#!/usr/bin/env python3
"""Offline tests for extracting prior emulator failures without inventing evidence."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from diagnose_campaign_evidence import render_markdown, summarize


class DiagnoseCampaignEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.roster = self.root / "roster"
        self.slot = self.root / "slot"
        (self.roster / "nested").mkdir(parents=True)
        (self.slot / "slot-01").mkdir(parents=True)

    def tearDown(self):
        self.temp.cleanup()

    def test_extracts_roster_failure_and_observation_metrics(self):
        (self.roster / "nested" / "roster.json").write_text(json.dumps({
            "status": "INSUFFICIENT_ELIGIBLE_CANDIDATES",
            "eligibleCount": 1,
            "requiredSlots": 20,
            "probesAttempted": 60,
            "stopReason": "probe_cap_reached",
            "rejectionCounts": {"http_503": 59},
        }))
        slot = self.slot / "slot-01"
        (slot / "assignment.json").write_text(json.dumps({"trainNo": "12089", "runDate": "12-10-2026"}))
        (slot / "slot-result.json").write_text(json.dumps({
            "status": "DEVICE_TEST_FAILED",
            "passed": False,
            "trainNo": "12089",
            "runDate": "12-10-2026",
            "failure": "AssertionError: only 40 receipts",
            "durationMs": 2_700_000,
            "observationCount": 46,
            "expectedObservationCount": 46,
            "screenPresentSamples": 46,
            "autoOnAtEverySample": True,
            "minuteRefreshReceipts": 40,
            "missedOrUnchangedReceiptSamples": 5,
            "autoOffAtEnd": True,
            "selectedSourceAtStart": "Selected NTES · official",
            "selectedSourceAtEnd": "Selected NTES · official",
            "verifiedAppScreenshots": ["screens/campaign-minute-00.png"],
        }))
        (slot / "observations.jsonl").write_text("\n".join(
            json.dumps({
                "minute": minute,
                "autoEnabled": True,
                "screenPresent": True,
                "newReceiptSincePrevious": minute <= 40,
                "receiptClock": f"10:{minute:02d}:00" if minute <= 40 else None,
                "sourceLabel": "NTES official",
            }) for minute in range(46)
        ) + "\n")
        (slot / "instrumentation.txt").write_text(
            "INSTRUMENTATION_STATUS_CODE: -2\n"
            "java.lang.AssertionError: only 40 receipts\nFAILURES!!!\n"
        )

        result = summarize(self.roster, self.slot)
        self.assertEqual(1, result["roster"]["eligibleCount"])
        self.assertEqual(60, result["roster"]["probesAttempted"])
        self.assertEqual("AssertionError: only 40 receipts", result["slot"]["failure"])
        self.assertEqual(46, result["slot"]["autoOnSamples"])
        self.assertEqual(40, result["slot"]["minuteRefreshReceipts"])
        self.assertEqual([], result["slot"]["missingMinutes"])
        self.assertTrue(any("FAILURES!!!" in line for line in result["slot"]["instrumentationSignals"]))
        markdown = render_markdown(result)
        self.assertIn("1/20", markdown)
        self.assertIn("not proof of train movement or GPS", markdown)

    def test_missing_observations_are_reported_not_filled(self):
        slot = self.slot / "slot-01"
        (slot / "slot-result.json").write_text(json.dumps({
            "status": "DEVICE_RUN_FAILED_OR_NO_RESULT", "passed": False,
            "reason": "instrumentation was interrupted",
        }))
        (slot / "observations.jsonl").write_text(json.dumps({
            "minute": 0, "screenPresent": True, "autoEnabled": True,
        }) + "\nnot-json\n")
        result = summarize(self.roster, self.slot)
        self.assertEqual([1, *range(2, 46)], result["slot"]["missingMinutes"])
        self.assertEqual(1, result["slot"]["malformedObservationLines"])
        self.assertEqual("instrumentation was interrupted", result["slot"]["failure"])
        self.assertEqual("unavailable", result["roster"]["status"])


if __name__ == "__main__":
    unittest.main()
