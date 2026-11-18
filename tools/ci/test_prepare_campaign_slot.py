#!/usr/bin/env python3
"""Offline tests for one-based, unique 20-slot assignment handling."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from prepare_campaign_slot import prepare


class PrepareCampaignSlotTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.roster = self.root / "roster.json"
        self.evidence = self.root / "slot-01"

    def tearDown(self):
        self.temp.cleanup()

    def test_preparation_failure_never_assigns_a_train(self):
        should_run = prepare(self.roster, "failure", 1, self.evidence)
        self.assertFalse(should_run)
        result = json.loads((self.evidence / "slot-result.json").read_text())
        self.assertEqual("PREPARATION_FAILED", result["status"])
        self.assertFalse((self.evidence / "assignment.json").exists())

    def test_slot_uses_only_its_unique_roster_assignment(self):
        self.roster.write_text(json.dumps({
            "status": "INSUFFICIENT_ELIGIBLE_CANDIDATES",
            "eligibleCount": 1,
            "assignments": [{"slot": 1, "trainNo": "12089", "runDate": "12-10-2026"}],
        }))
        self.assertTrue(prepare(self.roster, "success", 1, self.evidence))
        assignment = json.loads((self.evidence / "assignment.json").read_text())
        self.assertEqual("12089", assignment["trainNo"])
        self.assertFalse(prepare(self.roster, "success", 2, self.root / "slot-02"))
        missing = json.loads((self.root / "slot-02" / "slot-result.json").read_text())
        self.assertEqual("NO_ELIGIBLE_CANDIDATE", missing["status"])

    def test_duplicate_train_numbers_in_roster_invalidate_slots(self):
        self.roster.write_text(json.dumps({
            "status": "READY", "eligibleCount": 20,
            "assignments": [
                {"slot": 1, "trainNo": "12089"},
                {"slot": 2, "trainNo": "12089"},
            ],
        }))
        self.assertFalse(prepare(self.roster, "success", 1, self.evidence))
        result = json.loads((self.evidence / "slot-result.json").read_text())
        self.assertEqual("ROSTER_INVALID_OR_UNAVAILABLE", result["status"])
        self.assertFalse((self.evidence / "assignment.json").exists())


if __name__ == "__main__":
    unittest.main()
