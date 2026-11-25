#!/usr/bin/env python3
"""Offline tests for the strict, no-false-red Android campaign runner gate."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from verify_campaign_runner_result import EXPECTED_MINUTES, verify


class VerifyCampaignRunnerResultTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "screens").mkdir()
        self.assignment = {"slot": 1, "trainNo": "17019", "runDate": "17-11-2026"}
        self.eligibility_before = {
            **self.assignment,
            "eligible": True,
            "scheduleEligibilityAtStart": "scheduled-active-now",
            "preflightSourceStampAgeSecondsAtStart": 40,
            "checkedAtUtc": "2026-11-17T11:30:00Z",
        }
        self.eligibility_after = {
            **self.assignment,
            "eligible": True,
            "scheduleEligibilityAtStart": "scheduled-active-now",
            "preflightSourceStampAgeSecondsAtStart": 75,
            "checkedAtUtc": "2026-11-17T11:30:35Z",
        }
        self.result = {
            "passed": True,
            "flowPassed": True,
            "slot": 1,
            "trainNo": "17019",
            "runDate": "17-11-2026",
            "durationMs": 2_733_176,
            "startedAtUtc": "2026-11-17T11:31:00Z",
            "endedAtUtc": "2026-11-17T12:16:33.176Z",
            "observationCount": 46,
            "expectedObservationCount": 46,
            "screenPresentSamples": 46,
            "autoOnAtEverySample": True,
            "eventTimeQualifiedAtEverySample": True,
            "autoEnabledAtStart": True,
            "minuteRefreshReceipts": 45,
            "missedOrUnchangedReceiptSamples": 0,
            "autoOffAtEnd": True,
            "deviceStartEligible": True,
            "deviceStartScheduleEligibility": "scheduled-active-now",
            "selectedSourceAtStart": "Selected NTES · official; direct request was fresh",
            "selectedSourceAtEnd": "Selected NTES · official; direct request was fresh",
            "verifiedAppScreenshots": [
                f"screens/campaign-minute-{minute:02d}.png" for minute in (0, 15, 30, 45)
            ],
        }
        self.observations = [
            {
                "minute": minute,
                "sampledAtIst": f"2026-11-17T17:{minute:02d}:00+05:30",
                "screenPresent": True,
                "autoEnabled": True,
                "trainNo": "17019",
                "runDate": "17-11-2026",
                "route": "SC → HYB",
                "sourceDescription": "Selected NTES · official · last refresh checked",
                "eventDescription": "Last NTES event at Test Station, 16:30; 30 min; present position unknown",
                "eventTimeQualified": True,
                "newReceiptSincePrevious": minute != 0,
            }
            for minute in EXPECTED_MINUTES
        ]
        for minute in (0, 15, 30, 45):
            header = b"\x89PNG\r\n\x1a\n" + b"\x00" * 8 + (640).to_bytes(4, "big") + (480).to_bytes(4, "big")
            (self.root / "screens" / f"campaign-minute-{minute:02d}.png").write_bytes(
                header + b"x" * 1100
            )
            (self.root / "screens" / f"campaign-minute-{minute:02d}.xml").write_text(
                f'<hierarchy><node package="in.opentrack.india">{minute}</node>' + "x" * 120 + "</hierarchy>"
            )
        self.instrumentation = "Time: 2,733.176\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n"

    def tearDown(self):
        self.temp.cleanup()

    def run_verify(self, result=None, observations=None, instrumentation=None,
                   instrumentation_exit_code=0, assignment=None,
                   eligibility_before=None, eligibility_after=None):
        return verify(
            result if result is not None else self.result,
            observations if observations is not None else self.observations,
            instrumentation if instrumentation is not None else self.instrumentation,
            instrumentation_exit_code,
            self.root,
            assignment if assignment is not None else self.assignment,
            eligibility_before if eligibility_before is not None else self.eligibility_before,
            eligibility_after if eligibility_after is not None else self.eligibility_after,
        )

    def test_accepts_complete_real_world_shaped_evidence(self):
        self.assertEqual([], self.run_verify())

    def test_rejects_missing_or_inconsistent_evidence_without_crashing(self):
        broken_result = dict(self.result)
        broken_result["minuteRefreshReceipts"] = 42
        broken_observations = [item for item in self.observations if item["minute"] != 17]
        broken_observations[3] = dict(broken_observations[3], autoEnabled=False)
        errors = self.run_verify(
            result=broken_result,
            observations=broken_observations,
            instrumentation="FAILURES!!!\n",
            instrumentation_exit_code=1,
        )
        self.assertTrue(any("exited with 1" in error for error in errors))
        self.assertTrue(any("minute 17" in error for error in errors))
        self.assertTrue(any("Auto" in error for error in errors))
        self.assertTrue(any("receipts" in error for error in errors))

    def test_rejects_malformed_and_non_numeric_minutes(self):
        observations = list(self.observations)
        observations[0] = dict(observations[0], minute="0")
        errors = self.run_verify(observations=observations)
        self.assertTrue(any("exactly one sample" in error for error in errors))

    def test_rejects_ntes_receipt_or_update_stamp_without_fresh_event_clock(self):
        observations = [dict(item) for item in self.observations]
        observations[12]["eventDescription"] = "Last refresh checked 17:12; feed published at 17:12"
        observations[12]["eventTimeQualified"] = False
        result = dict(self.result, eventTimeQualifiedAtEverySample=False)
        errors = self.run_verify(result=result, observations=observations)
        self.assertTrue(any("event clock" in error.lower() for error in errors))
        self.assertTrue(any("event-time description" in error for error in errors))

        observations = [dict(item) for item in self.observations]
        observations[12]["eventDescription"] = "Last NTES event at Test Station, 12:00; 5 h"
        errors = self.run_verify(observations=observations)
        self.assertTrue(any("within 2 hours" in error for error in errors))

    def test_rejects_wrong_assignment_source_or_ineligible_host_gate(self):
        result = dict(self.result, trainNo="17020")
        bad_gate = dict(self.eligibility_after, eligible=False)
        errors = self.run_verify(result=result, eligibility_after=bad_gate)
        self.assertTrue(any("does not match its roster assignment" in error for error in errors))
        self.assertTrue(any("was not confirmed after-emulator-boot" in error for error in errors))

        malformed = dict(self.result, deviceStartScheduleEligibility=[])
        errors = self.run_verify(result=malformed)
        self.assertTrue(any("schedule category" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
