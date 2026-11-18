#!/usr/bin/env python3
"""Offline coverage for campaign PDF generation and evidence-side acceptance checks."""
from __future__ import annotations

import json
import random
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

from build_wimt_20_train_campaign_pdf import build_report


ASSIGNMENT = {
    "slot": 1,
    "trainNo": "12000",
    "trainName": "Synthetic Express",
    "runDate": "12-10-2026",
    "sourceStationCode": "HYB",
    "destinationStationCode": "SC",
    "scheduleEligibility": "scheduled-active-now",
    "scheduledDepartureMinute": 930,
    "scheduledArrivalMinute": 1110,
    "preflightSourceEvidence": {
        "httpStatus": 200,
        "freshSourceStamp": True,
        "sourceStampAgeSecondsAtProbe": 30,
        "runDateMatched": True,
        "routeMatched": True,
        "scheduleEndpointTimesMatched": True,
    },
}


def _chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def write_png(path: Path, seed: int) -> None:
    width = height = 64
    rng = random.Random(seed)
    raw = b"".join(b"\0" + rng.randbytes(width * 4) for _ in range(height))
    payload = (b"\x89PNG\r\n\x1a\n"
               + _chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
               + _chunk(b"IDAT", zlib.compress(raw))
               + _chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)


class CampaignPdfTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.roster = self.root / "roster.json"
        self.slots = self.root / "slots"
        self.pdf = self.root / "report.pdf"
        self.json = self.root / "report.json"
        self.markdown = self.root / "report.md"

    def tearDown(self):
        self.temp.cleanup()

    def build(self):
        return build_report(self.roster, self.slots, self.pdf, self.json, self.markdown,
                            {"run_id": "123", "sha": "abc123", "ref_name": "test"})

    def test_unavailable_slots_still_produce_honest_report(self):
        self.roster.write_text(json.dumps({
            "status": "INSUFFICIENT_ELIGIBLE_CANDIDATES",
            "requiredSlots": 20,
            "eligibleCount": 0,
            "assignments": [],
        }))
        directory = self.slots / "slot-01"
        directory.mkdir(parents=True)
        (directory / "slot-result.json").write_text(json.dumps({
            "slot": 1, "status": "NO_ELIGIBLE_CANDIDATE", "trainNo": None,
            "reason": "no candidate was independently assigned",
        }))

        report = self.build()
        self.assertTrue(self.pdf.read_bytes().startswith(b"%PDF"))
        self.assertEqual("NO_ELIGIBLE_ROSTER_OR_PREPARATION_FAILED", report["verdict"])
        self.assertEqual(0, report["assignedDistinctTrains"])
        self.assertEqual(20, len(report["slots"]))
        self.assertEqual("NO_ELIGIBLE_CANDIDATE", report["slots"][0]["status"])
        self.assertEqual(0, report["totalMinuteSlotsObserved"])
        self.assertIn("20-train, 45-minute emulator campaign", self.markdown.read_text())

    def test_final_preflight_rejection_is_reported_as_not_dispatched_not_failed_soak(self):
        self.roster.write_text(json.dumps({
            "status": "FINAL_PREFLIGHT_REJECTED",
            "requiredSlots": 20,
            "eligibleCount": 0,
            "candidateRosterEligibleCount": 7,
            "assignments": [],
            "stopReason": "Only seven fresh candidates remained; no emulator was dispatched.",
            "finalPreflightStatus": "REJECTED",
            "initialPreflight": {"status": "READY", "eligibleCount": 20, "probesAttempted": 20},
            "finalValidation": {"errors": ["roster has 7 assignments, not 20"]},
        }))

        report = self.build()
        self.assertEqual("NO_ELIGIBLE_ROSTER_OR_PREPARATION_FAILED", report["verdict"])
        self.assertEqual(0, report["assignedDistinctTrains"])
        self.assertEqual(7, report["candidateRosterEligibleCount"])
        self.assertEqual("REJECTED", report["finalPreflightStatus"])
        self.assertEqual("NOT_DISPATCHED_ROSTER_GATE_BLOCKED", report["slots"][0]["status"])
        markdown = self.markdown.read_text()
        self.assertIn("Only seven fresh candidates remained", markdown)
        self.assertIn("Initial admission scan: **20/20**", markdown)

    def test_report_rejects_a_bare_pass_flag_without_observation_artifacts(self):
        self.roster.write_text(json.dumps({
            "status": "INSUFFICIENT_ELIGIBLE_CANDIDATES",
            "eligibleCount": 1,
            "assignments": [ASSIGNMENT],
        }))
        directory = self.slots / "slot-01"
        directory.mkdir(parents=True)
        (directory / "slot-result.json").write_text(json.dumps({
            "slot": 1, "trainNo": "12000", "runDate": "12-10-2026", "passed": True,
        }))

        report = self.build()
        slot = report["slots"][0]
        self.assertEqual("RESULT_REJECTED_INCOMPLETE_EVIDENCE", slot["status"])
        self.assertIn("observation_minutes_incomplete_or_duplicated", slot["reportValidationFailures"])
        self.assertEqual(0, report["completed45MinuteUiTrials"])
        self.assertEqual("PARTIAL_OR_INCOMPLETE", report["verdict"])

    def test_full_slot_pass_requires_46_samples_43_receipts_and_verified_images(self):
        self.roster.write_text(json.dumps({
            "status": "INSUFFICIENT_ELIGIBLE_CANDIDATES",
            "eligibleCount": 1,
            "assignments": [ASSIGNMENT],
        }))
        directory = self.slots / "slot-01"
        screens = directory / "screens"
        observations = []
        for minute in range(46):
            name = None
            sample_minute = 930 + minute
            sample_clock = f"{sample_minute // 60:02d}:{sample_minute % 60:02d}"
            if minute in {0, 15, 30, 45}:
                name = f"screens/campaign-minute-{minute:02d}.png"
                image = directory / name
                write_png(image, minute)
                image.with_suffix(".xml").write_text(
                    '<hierarchy rotation="0"><node package="in.opentrack.india" '
                    'text="synthetic app hierarchy" bounds="[0,0][1080,1920]" /></hierarchy>',
                    encoding="utf-8",
                )
            observations.append({
                "minute": minute,
                "elapsedMs": minute * 60_000,
                "sampledAtIst": f"2026-10-12T{sample_clock}:00+05:30",
                "screenPresent": True,
                "autoEnabled": True,
                "trainNo": "12000",
                "runDate": "run of 12 Oct 2026",
                "route": "HYB → SC",
                "headline": "Synthetic observation",
                "provenance": "Synthetic test only",
                "age": "test age",
                "sourceSummary": "test summary",
                "sourceLabel": "NTES",
                "sourceDescription": "Selected NTES · official",
                "receiptClock": sample_clock + ":00" if minute else None,
                "receiptDescription": "Checked synthetic receipt" if 1 <= minute <= 43 else "",
                "newReceiptSincePrevious": 1 <= minute <= 43,
                "screenshot": name,
            })
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "observations.jsonl").write_text(
            "".join(json.dumps(row) + "\n" for row in observations), encoding="utf-8")
        selected = "Selected NTES · official. Choose NTES or the website."
        gate = {
            "eligible": True,
            "checkedAtUtc": "2026-10-12T09:59:00Z",
            "scheduleEligibilityAtStart": "scheduled-active-now",
            "preflightSourceStampAgeSecondsAtStart": 60,
            "reasons": [],
        }
        (directory / "eligibility-before-emulator.json").write_text(json.dumps(gate), encoding="utf-8")
        (directory / "eligibility-after-emulator-boot.json").write_text(json.dumps(gate), encoding="utf-8")
        (directory / "slot-result.json").write_text(json.dumps({
            "slot": 1,
            "trainNo": "12000",
            "runDate": "12-10-2026",
            "startedAtUtc": "2026-10-12T10:00:00Z",
            "endedAtUtc": "2026-10-12T10:45:00Z",
            "durationMs": 2_700_000,
            "observationCount": 46,
            "expectedObservationCount": 46,
            "screenPresentSamples": 46,
            "deviceStartEligible": True,
            "autoOnAtEverySample": True,
            "minuteRefreshReceipts": 43,
            "missedOrUnchangedReceiptSamples": 2,
            "autoEnabledAtStart": True,
            "autoOffAtEnd": True,
            "selectedSourceAtStart": selected,
            "selectedSourceAtEnd": selected,
            "verifiedAppScreenshots": [row["screenshot"] for row in observations if row["screenshot"]],
            "passed": True,
        }), encoding="utf-8")

        report = self.build()
        slot = report["slots"][0]
        self.assertEqual("PASS_45_MINUTE_UI_TRIAL", slot["status"])
        self.assertEqual(1, report["completed45MinuteUiTrials"])
        self.assertEqual(46, slot["actualScreenObservations"])
        self.assertEqual(43, slot["minuteRefreshReceipts"])
        self.assertEqual([], slot["reportValidationFailures"])
        self.assertTrue(self.pdf.read_bytes().startswith(b"%PDF"))


if __name__ == "__main__":
    unittest.main()
