#!/usr/bin/env python3
"""Offline tests for the campaign's just-before-device eligibility gate."""
from __future__ import annotations

import unittest
from datetime import datetime, timedelta, timezone

from verify_campaign_assignment import check_assignment

NOW = datetime(2026, 10, 12, 10, 0, tzinfo=timezone.utc)  # 15:30 IST.


def assignment():
    return {
        "slot": 1,
        "trainNo": "12089",
        "runDate": "12-10-2026",
        "scheduledDepartureMinute": 970,
        "scheduledArrivalMinute": 1320,
        "preflightCheckedAtUtc": "2026-10-12T09:59:00Z",
        "preflightSourceEvidence": {
            "accepted": True,
            "runDateMatched": True,
            "routeMatched": True,
            "scheduleEndpointTimesMatched": True,
            "freshSourceStamp": True,
            "sourceStampAgeSecondsAtProbe": 30,
        },
    }


class CampaignAssignmentGateTest(unittest.TestCase):
    def test_due_train_remains_eligible_with_recent_preflight_evidence(self):
        result = check_assignment(assignment(), NOW)
        self.assertTrue(result["eligible"], result["reasons"])
        self.assertEqual("scheduled-departure-within-60-minutes",
                         result["scheduleEligibilityAtStart"])
        self.assertEqual(40.0, result["minutesToScheduledDepartureAtStart"])

    def test_due_candidate_can_become_active_without_being_rejected(self):
        candidate = assignment()
        candidate["scheduledDepartureMinute"] = 934  # 15:34 IST, four minutes after preflight.
        result = check_assignment(candidate, NOW + timedelta(minutes=4))
        self.assertTrue(result["eligible"], result["reasons"])
        self.assertEqual("scheduled-active-now", result["scheduleEligibilityAtStart"])

    def test_expired_schedule_window_and_stale_source_are_reported(self):
        expired = check_assignment(assignment(), NOW + timedelta(hours=8))
        self.assertFalse(expired["eligible"])
        self.assertIn("scheduled_train_not_active_or_due_within_60_minutes", expired["reasons"])
        stale = check_assignment(assignment(), NOW + timedelta(minutes=11))
        self.assertFalse(stale["eligible"])
        self.assertIn("preflight_wimt_source_stamp_not_fresh_at_device_start", stale["reasons"])

    def test_new_ist_date_invalidates_default_today_search_assignment(self):
        result = check_assignment(assignment(), datetime(2026, 10, 12, 18, 31, tzinfo=timezone.utc))
        self.assertFalse(result["eligible"])
        self.assertIn("run_date_is_no_longer_today", result["reasons"])


if __name__ == "__main__":
    unittest.main()
