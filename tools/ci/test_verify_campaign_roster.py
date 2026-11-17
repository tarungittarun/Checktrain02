#!/usr/bin/env python3
"""Offline tests for the fail-closed 20-train campaign roster gate."""
from __future__ import annotations

import copy
import unittest
from datetime import datetime, timezone

from verify_campaign_roster import verify_roster

NOW = datetime(2026, 10, 12, 10, 0, tzinfo=timezone.utc)  # 15:30 Asia/Kolkata.


def make_roster() -> dict:
    assignments = []
    for slot in range(1, 21):
        train_no = str(12000 + slot)
        assignments.append({
            "slot": slot,
            "trainNo": train_no,
            "trainName": f"Test Express {slot}",
            "sourceStationCode": "HYB",
            "destinationStationCode": "SC",
            "runDate": "12-10-2026",
            "originEpochDay": 20637,
            "scheduledDepartureMinute": 930,
            "scheduledArrivalMinute": 1140,
            "scheduleEligibility": "scheduled-active-now",
            "preflightCheckedAtUtc": "2026-10-12T09:59:30Z",
            "preflightSourceEvidence": {
                "httpStatus": 200,
                "accepted": True,
                "runDateMatched": True,
                "routeMatched": True,
                "scheduleEndpointTimesMatched": True,
                "freshSourceStamp": True,
                "sourceStampAgeSecondsAtProbe": 30,
                "sourceRowsWithActualFields": 1,
            },
        })
    return {
        "schemaVersion": 1,
        "campaign": "wimt-20-train-45-minute-emulator-campaign",
        "status": "READY",
        "requiredSlots": 20,
        "eligibleCount": 20,
        "probesAttempted": 20,
        "maxProbes": 60,
        "generatedAtUtc": "2026-10-12T10:00:00Z",
        "generatedAtIst": "2026-10-12T15:30:00+05:30",
        "timetable": {
            "timetableVintage": "2026-10-01",
            "timetableAgeDays": 11,
            "timetableFreshness": "recent",
            "knownRunDayCandidates": 20,
        },
        "sourceIdentity": {
            "name": "WIMT .in direct /cache/live_status response",
            "host": "whereismytrain.in",
            "responseBodiesStored": False,
            "retries": 0,
        },
        "assignments": assignments,
    }


class VerifyCampaignRosterTests(unittest.TestCase):
    def test_accepts_twenty_distinct_current_recent_and_source_checked_trains(self):
        self.assertEqual([], verify_roster(make_roster(), NOW))

    def test_rejects_duplicate_train_or_slot_instead_of_repairing_roster(self):
        roster = make_roster()
        roster["assignments"][1]["trainNo"] = roster["assignments"][0]["trainNo"]
        roster["assignments"][1]["slot"] = roster["assignments"][0]["slot"]
        errors = verify_roster(roster, NOW)
        self.assertTrue(any("duplicate train number" in error for error in errors))
        self.assertTrue(any("duplicate matrix slot" in error for error in errors))
        self.assertTrue(any("20 distinct" in error for error in errors))

    def test_rejects_stale_timetable_and_does_not_accept_source_freshness_as_a_substitute(self):
        roster = make_roster()
        roster["timetable"]["timetableAgeDays"] = 120
        roster["timetable"]["timetableFreshness"] = "stale"
        errors = verify_roster(roster, NOW)
        self.assertTrue(any("timetable source-history vintage" in error for error in errors))

    def test_rejects_expired_source_stamp_or_schedule_window(self):
        roster = make_roster()
        roster["assignments"][0]["preflightCheckedAtUtc"] = "2026-10-12T09:45:00Z"
        errors = verify_roster(roster, NOW)
        self.assertTrue(any("source stamp has expired" in error for error in errors))

        roster = make_roster()
        roster["assignments"][0]["scheduledDepartureMinute"] = 991  # 16:31 IST, 61 minutes away.
        roster["assignments"][0]["scheduledArrivalMinute"] = 1171
        roster["assignments"][0]["scheduleEligibility"] = "scheduled-departure-within-60-minutes"
        errors = verify_roster(roster, NOW)
        self.assertTrue(any("schedule window expired" in error for error in errors))

    def test_rejects_unconfirmed_source_route_date_or_schedule_fields(self):
        roster = make_roster()
        roster["assignments"][7]["preflightSourceEvidence"]["routeMatched"] = False
        errors = verify_roster(roster, NOW)
        self.assertTrue(any("routeMatched is not confirmed" in error for error in errors))

    def test_malformed_category_types_fail_closed_without_crashing(self):
        roster = make_roster()
        roster["assignments"][0]["scheduleEligibility"] = ["scheduled-active-now"]
        errors = verify_roster(roster, NOW)
        self.assertTrue(any("not scheduled active now" in error for error in errors))

    def test_rejects_roster_with_fewer_than_twenty_assignments(self):
        roster = make_roster()
        roster["assignments"].pop()
        roster["eligibleCount"] = 19
        errors = verify_roster(roster, NOW)
        self.assertTrue(any("assignments, not 20" in error for error in errors))
        self.assertTrue(any("eligibleCount is not 20" in error for error in errors))

    def test_does_not_mutate_the_roster(self):
        roster = make_roster()
        before = copy.deepcopy(roster)
        verify_roster(roster, NOW)
        self.assertEqual(before, roster)


if __name__ == "__main__":
    unittest.main()
