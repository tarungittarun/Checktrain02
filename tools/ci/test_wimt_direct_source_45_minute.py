#!/usr/bin/env python3
"""Offline tests for the 45-minute direct WIMT `.in` source observer."""
from __future__ import annotations

import json
import sqlite3
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

import wimt_direct_source_45_minute as direct

UTC = timezone.utc


class FakeClock:
    def __init__(self, start: datetime):
        self.wall = start.astimezone(UTC)
        self.elapsed = 0.0

    def now(self) -> datetime:
        return self.wall

    def monotonic(self) -> float:
        return self.elapsed

    def sleep(self, seconds: float) -> None:
        self.elapsed += seconds
        self.wall += timedelta(seconds=seconds)


class DirectSourceObserverTests(unittest.TestCase):
    def setUp(self):
        self.candidate = {
            "trainNo": "12627",
            "runDate": "12-10-2026",
            "sourceStationCode": "MYS",
            "destinationStationCode": "TLGP",
            "scheduledDepartureMinute": 8 * 60,
            "scheduledArrivalMinute": 20 * 60,
            "scheduleEligibility": "scheduled-active-now",
            "scheduledMinutesRemainingAtSelection": 90,
        }
        self.clock = FakeClock(datetime(2026, 10, 12, 13, 0, tzinfo=UTC))

    def body(self, *, stamp: datetime | None = None, current: str = "MYS",
             actual: bool = True, source: str = "status", route: str = "MYS") -> bytes:
        now = stamp or self.clock.now()
        rows = [
            {
                "station_code": "MYS",
                "sch_departure_time": "08:00",
                "source": source,
                "departed": True,
                **({"actual_departure_time": "08:05"} if actual else {}),
            },
            {
                "station_code": "TLGP",
                "sch_arrival_time": "20:00",
                "source": "eta",
            },
        ]
        return json.dumps({
            "train_no": self.candidate["trainNo"],
            "start_date": self.candidate["runDate"],
            "source_station": route,
            "destination_station": "TLGP",
            "lastUpdateIsoDate": now.isoformat(),
            "curStn": current,
            "days_schedule": rows,
        }).encode("utf-8")

    def response(self, **kwargs) -> direct.SourceResponse:
        payload = self.body(**kwargs)
        return direct.SourceResponse(
            status=200,
            body=payload,
            received_at_utc=self.clock.now(),
            response_bytes=len(payload),
            body_sha256="a" * 64,
        )

    def test_fresh_matched_current_report_with_an_actual_stop_clock_qualifies(self):
        sample = direct.assess_response(self.candidate, self.response())
        self.assertTrue(sample["runDateMatched"])
        self.assertTrue(sample["routeMatched"])
        self.assertTrue(sample["freshSourceStamp"])
        self.assertEqual("MYS", sample["currentStationCodeClaim"])
        self.assertEqual(1, sample["sourceActualStopRowCount"])
        self.assertTrue(sample["runningStatusQualified"])
        self.assertTrue(sample["protocolChecksPassed"])
        self.assertTrue(sample["qualified"])

    def test_stale_source_stamp_does_not_qualify(self):
        stale = self.clock.now() - timedelta(minutes=11)
        sample = direct.assess_response(self.candidate, self.response(stamp=stale))
        self.assertFalse(sample["freshSourceStamp"])
        self.assertFalse(sample["runningStatusQualified"])
        self.assertFalse(sample["qualified"])
        self.assertIn("source_stamp_stale", sample["failureReasons"])

    def test_eta_or_missing_actual_clock_does_not_qualify_as_running(self):
        eta = direct.assess_response(self.candidate, self.response(actual=True, source="eta"))
        missing = direct.assess_response(self.candidate, self.response(actual=False))
        self.assertEqual(0, eta["sourceActualStopRowCount"])
        self.assertEqual(0, missing["sourceActualStopRowCount"])
        self.assertFalse(eta["qualified"])
        self.assertFalse(missing["qualified"])

    def test_unmatched_route_and_missing_station_are_rejected(self):
        route_mismatch = direct.assess_response(self.candidate, self.response(route="SBC"))
        no_station = direct.assess_response(self.candidate, self.response(current="?"))
        self.assertFalse(route_mismatch["routeMatched"])
        self.assertFalse(route_mismatch["qualified"])
        self.assertFalse(no_station["currentStationClaimPresent"])
        self.assertFalse(no_station["qualified"])

    def test_ten_five_minute_checkpoints_span_the_full_45_minutes(self):
        initial = direct.assess_response(self.candidate, self.response())
        callbacks = []
        fetched = []

        def fetch(_candidate):
            fetched.append(_candidate["trainNo"])
            payload = self.body()
            return direct.SourceResponse(
                status=200, body=payload, received_at_utc=self.clock.now(),
                response_bytes=len(payload), body_sha256="b" * 64,
            )

        result = direct.observe_candidate(
            self.candidate, initial, fetch,
            monotonic=self.clock.monotonic, sleep=self.clock.sleep, now=self.clock.now,
            on_sample=callbacks.append,
        )
        self.assertTrue(result["passed"])
        self.assertEqual(45.0, result["durationMinutes"])
        self.assertEqual(list(range(0, 46, 5)), result["recordedCheckpointMinutes"])
        self.assertEqual(10, len(result["samples"]))
        self.assertEqual(9, len(fetched))
        self.assertEqual(10, len(callbacks))
        self.assertTrue(all(item["qualified"] for item in result["samples"]))

    def test_source_loss_is_recorded_but_the_check_keeps_its_45_minute_window(self):
        initial = direct.assess_response(self.candidate, self.response())
        call_number = 0

        def fetch(_candidate):
            nonlocal call_number
            call_number += 1
            if call_number == 2:
                return direct.SourceResponse(
                    status=200, body=self.body(actual=False), received_at_utc=self.clock.now(),
                    response_bytes=300, body_sha256="c" * 64,
                )
            payload = self.body()
            return direct.SourceResponse(
                status=200, body=payload, received_at_utc=self.clock.now(),
                response_bytes=len(payload), body_sha256="d" * 64,
            )

        result = direct.observe_candidate(
            self.candidate, initial, fetch,
            monotonic=self.clock.monotonic, sleep=self.clock.sleep, now=self.clock.now,
        )
        self.assertFalse(result["passed"])
        self.assertEqual(45.0, result["durationMinutes"])
        self.assertEqual(10, len(result["samples"]))
        self.assertFalse(result["samples"][2]["qualified"])
        self.assertFalse(result["checks"][
            "freshMatchedWimtInCurrentStationAndActualClockAtEveryCheckpoint"])

    def test_refusal_is_not_retried_or_misreported_as_a_45_minute_observation(self):
        initial = direct.assess_response(self.candidate, self.response())
        fetched = []

        def fetch(_candidate):
            fetched.append(True)
            return direct.SourceResponse(
                status=429, body=b"", received_at_utc=self.clock.now(),
            )

        result = direct.observe_candidate(
            self.candidate, initial, fetch,
            monotonic=self.clock.monotonic, sleep=self.clock.sleep, now=self.clock.now,
        )
        self.assertFalse(result["passed"])
        self.assertEqual(429, result["refusalStatus"])
        self.assertEqual([0, 5], result["recordedCheckpointMinutes"])
        self.assertEqual(1, len(fetched))
        self.assertFalse(result["checks"]["elapsedAtLeast45Minutes"])

    def test_candidate_scan_selects_only_a_qualified_direct_source_reply(self):
        first = dict(self.candidate, trainNo="12628")
        selected, sample, attempts, stop = direct._select_live_candidate(
            [first, self.candidate],
            lambda item: self.response(route="SBC") if item["trainNo"] == "12628"
            else self.response(),
            sleep=lambda _: None,
        )
        self.assertEqual("12627", selected["trainNo"])
        self.assertTrue(sample["qualified"])
        self.assertEqual(2, len(attempts))
        self.assertIsNone(stop)

    def test_run_writes_redacted_direct_source_evidence_end_to_end(self):
        with tempfile.TemporaryDirectory(prefix="wimt-direct-test-") as temp_name:
            root = Path(temp_name)
            db_path = root / "refdb.db"
            output = root / "evidence"
            db = sqlite3.connect(db_path)
            db.executescript("""
                CREATE TABLE meta (k TEXT, v TEXT);
                CREATE TABLE trains (
                    train_no TEXT, name TEXT, src_code TEXT, dst_code TEXT,
                    run_days INTEGER, num_stops INTEGER
                );
                CREATE TABLE stops (
                    train_no TEXT, seq INTEGER, arrival_min INTEGER, departure_min INTEGER
                );
            """)
            db.executemany("INSERT INTO meta(k,v) VALUES(?,?)", [
                ("timetable_age_days", "1"),
                ("timetable_freshness", "recent"),
                ("timetable_vintage", "2026-10-11"),
                ("source_vintage", "2026-10-11"),
                ("run_days_vintage", "2026-10-11"),
                ("data_trust", "fixture"),
            ])
            db.execute("INSERT INTO trains VALUES(?,?,?,?,?,?)",
                       ("12627", "Fixture Express", "MYS", "TLGP", 127, 2))
            db.executemany("INSERT INTO stops VALUES(?,?,?,?)", [
                ("12627", 1, 470, 480),
                ("12627", 2, 1439, 1440),
            ])
            db.commit()
            db.close()

            def fetch(candidate):
                now = self.clock.now()
                payload = json.dumps({
                    "train_no": candidate["trainNo"],
                    "start_date": candidate["runDate"],
                    "source_station": candidate["sourceStationCode"],
                    "destination_station": candidate["destinationStationCode"],
                    "lastUpdateIsoDate": now.isoformat(),
                    "curStn": candidate["sourceStationCode"],
                    "days_schedule": [
                        {"station_code": candidate["sourceStationCode"],
                         "sch_departure_time": "08:00", "departed": True,
                         "actual_departure_time": "08:05", "source": "status"},
                        {"station_code": candidate["destinationStationCode"],
                         "sch_arrival_time": "23:59", "source": "eta"},
                    ],
                }).encode("utf-8")
                return direct.SourceResponse(
                    status=200, body=payload, received_at_utc=now,
                    response_bytes=len(payload), body_sha256="e" * 64,
                )

            result = direct.run_observation(
                db_path, output, fetch=fetch, now=self.clock.now,
                sleep=self.clock.sleep, monotonic=self.clock.monotonic,
            )
            saved = json.loads((output / "result.json").read_text(encoding="utf-8"))
            trace = (output / "trace.jsonl").read_text(encoding="utf-8").splitlines()
            self.assertTrue(result["passed"])
            self.assertTrue(saved["passed"])
            self.assertEqual("direct-source-only", saved["mode"])
            self.assertEqual("12627", saved["trainNo"])
            self.assertEqual(45.0, saved["observation"]["durationMinutes"])
            self.assertEqual(10, sum('"event": "observation_sample"' in line for line in trace))
            self.assertTrue((output / "report.md").is_file())
            self.assertIn("no APK", saved["reportMarkdown"])


if __name__ == "__main__":
    unittest.main()
