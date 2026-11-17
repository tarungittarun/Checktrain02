#!/usr/bin/env python3
"""Synthetic offline coverage for the campaign timetable selector and WIMT gates."""
from __future__ import annotations

import gzip
import json
import sqlite3
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock
import urllib.error

from select_wimt_campaign_roster import (
    ProbeFailure,
    build_roster,
    epoch_day,
    fetch_once,
    parse_clock,
    station_code,
    validate_reply,
)

NOW = datetime(2026, 10, 12, 10, 0, tzinfo=timezone.utc)  # 15:30 Asia/Kolkata, Monday.
STAMP = "2026-10-12T15:29:00+05:30"


def make_db(path: Path, count: int = 20, start_minute: int = 930) -> None:
    db = sqlite3.connect(path)
    db.executescript("""
        CREATE TABLE meta (k TEXT, v TEXT);
        INSERT INTO meta VALUES ('timetable_vintage', '2026-10-01');
        INSERT INTO meta VALUES ('timetable_age_days', '11');
        INSERT INTO meta VALUES ('timetable_freshness', 'fresh');
        INSERT INTO meta VALUES ('data_trust', 'verified');
        CREATE TABLE trains (
            train_no TEXT, name TEXT, src_code TEXT, dst_code TEXT,
            run_days INTEGER, num_stops INTEGER
        );
        CREATE TABLE stops (
            train_no TEXT, seq INTEGER, station_code TEXT,
            arrival_min INTEGER, departure_min INTEGER
        );
    """)
    for i in range(count):
        train = f"{12000 + i}"
        db.execute("INSERT INTO trains VALUES (?, ?, ?, ?, ?, ?)",
                   (train, f"Test Express {i}", "HYB", "SC", 2, 2))
        db.execute("INSERT INTO stops VALUES (?, 1, 'HYB', ?, ?)",
                   (train, start_minute, start_minute))
        db.execute("INSERT INTO stops VALUES (?, 2, 'SC', ?, ?)",
                   (train, start_minute + 180, start_minute + 180))
    db.commit()
    db.close()


def reply(train: str, *, stamp: str = STAMP, start_date: str = "12-10-2026",
          source: str = "HYB", destination: str = "SC", source_clock: str = "15:30",
          destination_clock: str = "18:30", current: str = "KZJ") -> bytes:
    return json.dumps({
        "train_no": train,
        "start_date": start_date,
        "source_station_code": source,
        "destination_station_code": destination,
        "lastUpdateIsoDate": stamp,
        "curStn": current,
        "days_schedule": [
            {"station_code": source, "sch_departure_time": source_clock,
             "actual_departure_time": "15:24", "source": "live"},
            {"station_code": destination, "sch_arrival_time": destination_clock,
             "source": "eta"},
        ],
    }).encode()


class CampaignRosterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp.name) / "refdb.db"
        make_db(self.db_path)

    def tearDown(self):
        self.temp.cleanup()

    def test_exactly_twenty_candidates_get_distinct_eligible_assignments(self):
        calls = []

        def fetch(train, run_date):
            calls.append((train, run_date))
            return 200, reply(train)

        roster = build_roster(self.db_path, NOW, fetch=fetch, sleep=lambda _: None)
        self.assertEqual("READY", roster["status"])
        self.assertEqual(20, roster["eligibleCount"])
        self.assertEqual(20, len({item["trainNo"] for item in roster["assignments"]}))
        self.assertEqual(20, len(calls))
        self.assertTrue(all(item["preflightSourceEvidence"]["freshSourceStamp"]
                            for item in roster["assignments"]))
        self.assertTrue(all(item["preflightSourceEvidence"]["scheduleEndpointTimesMatched"]
                            for item in roster["assignments"]))
        self.assertEqual(epoch_day(datetime(2026, 10, 12).date()),
                         roster["assignments"][0]["originEpochDay"])

    def test_missing_run_date_is_rejected_without_body_logging(self):
        payload = json.loads(reply("12000"))
        payload.pop("start_date")
        with self.assertRaisesRegex(ProbeFailure, "run_date_not_echoed"):
            validate_reply({
                "trainNo": "12000", "runDate": "12-10-2026", "sourceStationCode": "HYB",
                "destinationStationCode": "SC", "scheduledDepartureMinute": 930,
                "scheduledArrivalMinute": 1110, "scheduleEligibility": "scheduled-active-now",
            }, 200, json.dumps(payload).encode(), NOW)

    def test_wrong_route_date_or_old_source_stamp_is_rejected(self):
        candidate = {
            "trainNo": "12000", "runDate": "12-10-2026", "sourceStationCode": "HYB",
            "destinationStationCode": "SC", "scheduledDepartureMinute": 930,
            "scheduledArrivalMinute": 1110, "scheduleEligibility": "scheduled-active-now",
        }
        with self.assertRaisesRegex(ProbeFailure, "route_endpoints_mismatch"):
            validate_reply(candidate, 200, reply("12000", destination="NDLS"), NOW)
        with self.assertRaisesRegex(ProbeFailure, "run_date_mismatch"):
            validate_reply(candidate, 200, reply("12000", start_date="11-10-2026"), NOW)
        with self.assertRaisesRegex(ProbeFailure, "source_stamp_stale"):
            validate_reply(candidate, 200, reply("12000", stamp="2026-10-12T14:00:00+05:30"), NOW)

    def test_scheduled_departure_must_be_within_next_hour(self):
        # 16:31 IST is not eligible at 15:30 IST.
        other = Path(self.temp.name) / "late.db"
        make_db(other, count=20, start_minute=991)
        calls = []
        roster = build_roster(other, NOW,
                              fetch=lambda train, run_date: (calls.append((train, run_date)) or
                                                             (200, reply(train))),
                              sleep=lambda _: None)
        self.assertEqual("INSUFFICIENT_ELIGIBLE_CANDIDATES", roster["status"])
        self.assertEqual(0, len(calls))
        self.assertEqual(0, roster["eligibleCount"])

    def test_insufficient_live_responses_remain_explicit(self):
        def fetch(train, _run_date):
            if train in {"12000", "12001"}:
                return 200, reply(train)
            return 503, b"not saved"

        roster = build_roster(self.db_path, NOW, fetch=fetch, sleep=lambda _: None)
        self.assertEqual("INSUFFICIENT_ELIGIBLE_CANDIDATES", roster["status"])
        self.assertEqual(2, roster["eligibleCount"])
        self.assertEqual(5, roster["probesAttempted"])
        self.assertEqual("three_consecutive_transport_or_5xx_failures", roster["stopReason"])
        self.assertNotIn("not saved", json.dumps(roster))

    def test_run_day_bitmap_excludes_tuesday_only_train(self):
        db = sqlite3.connect(self.db_path)
        db.execute("UPDATE trains SET run_days=4 WHERE train_no='12000'")
        db.commit()
        db.close()
        calls = []
        roster = build_roster(self.db_path, NOW,
                              fetch=lambda train, run_date: (calls.append((train, run_date)) or
                                                             (200, reply(train))),
                              sleep=lambda _: None)
        self.assertEqual(19, roster["timetable"]["knownRunDayCandidates"])
        self.assertEqual(19, roster["eligibleCount"])
        self.assertNotIn("12000", {item[0] for item in calls})

    def test_known_timetable_train_id_conflicts_are_not_probed(self):
        other = Path(self.temp.name) / "conflicts.db"
        make_db(other, count=0)
        db = sqlite3.connect(other)
        for train, source, destination in (("16221", "MYS", "MAS"),
                                          ("16228", "SMET", "SBC")):
            db.execute("INSERT INTO trains VALUES (?, 'Known conflict', ?, ?, 2, 2)",
                       (train, source, destination))
            db.execute("INSERT INTO stops VALUES (?, 1, ?, 930, 930)", (train, source))
            db.execute("INSERT INTO stops VALUES (?, 2, ?, 1110, 1110)", (train, destination))
        db.commit()
        db.close()
        calls = []
        roster = build_roster(other, NOW,
                              fetch=lambda train, run_date: (calls.append((train, run_date)) or
                                                             (200, reply(train))),
                              sleep=lambda _: None)
        self.assertEqual(0, roster["eligibleCount"])
        self.assertEqual([], calls)
        self.assertEqual(2, roster["timetable"]["scheduleFilterRejections"]
                         ["known_timetable_train_id_conflict"])

    def test_retired_zero_prefixed_train_ids_are_not_probed(self):
        other = Path(self.temp.name) / "retired.db"
        make_db(other, count=0)
        db = sqlite3.connect(other)
        db.execute("INSERT INTO trains VALUES ('01234', 'Retired ID', 'HYB', 'SC', 2, 2)")
        db.execute("INSERT INTO stops VALUES ('01234', 1, 'HYB', 930, 930)")
        db.execute("INSERT INTO stops VALUES ('01234', 2, 'SC', 1110, 1110)")
        db.commit()
        db.close()
        calls = []
        roster = build_roster(other, NOW,
                              fetch=lambda train, run_date: (calls.append((train, run_date)) or
                                                             (200, reply(train))),
                              sleep=lambda _: None)
        self.assertEqual(0, roster["eligibleCount"])
        self.assertEqual([], calls)
        self.assertEqual(1, roster["timetable"]["scheduleFilterRejections"]
                         ["retired_zero_prefixed_train_number"])

    def test_oversized_source_stops_without_logging_payload(self):
        candidate = {
            "trainNo": "12000", "runDate": "12-10-2026", "sourceStationCode": "HYB",
            "destinationStationCode": "SC", "scheduledDepartureMinute": 930,
            "scheduledArrivalMinute": 1110, "scheduleEligibility": "scheduled-active-now",
        }
        with self.assertRaisesRegex(ProbeFailure, "response_too_large"):
            validate_reply(candidate, 200, b"x" * 1_000_001, NOW)
        self.assertEqual(17, parse_clock("00:17"))
        self.assertEqual(1457, parse_clock("24:17"))
        self.assertEqual("ABCDEF", station_code("abcdef"))
        self.assertIsNone(station_code("A"))
        self.assertIsNone(parse_clock("bad"))

    def test_gzip_db_asset_is_supported(self):
        gz_path = Path(self.temp.name) / "refdb.dbgz"
        with self.db_path.open("rb") as source, gzip.open(gz_path, "wb") as target:
            target.write(source.read())
        calls = []
        roster = build_roster(gz_path, NOW,
                              fetch=lambda train, run_date: (calls.append((train, run_date)) or
                                                             (200, reply(train))),
                              sleep=lambda _: None)
        self.assertEqual(20, roster["timetable"]["knownRunDayCandidates"])
        self.assertEqual(20, len(calls))
        self.assertEqual(20, roster["eligibleCount"])

    def test_direct_probe_does_not_follow_a_redirect_to_another_source(self):
        opener = mock.Mock()
        opener.open.side_effect = urllib.error.HTTPError(
            "https://whereismytrain.in/cache/live_status", 302, "redirect", {}, None)
        with mock.patch("select_wimt_campaign_roster.urllib.request.build_opener",
                        return_value=opener):
            with self.assertRaisesRegex(ProbeFailure, "http_302"):
                fetch_once("12000", "12-10-2026")
        opener.open.assert_called_once()

    def test_http_429_is_not_retried(self):
        def fetch(*_args):
            return 429, b"rate limited"

        roster = build_roster(self.db_path, NOW, fetch=fetch, sleep=lambda _: None)
        self.assertEqual(1, roster["probesAttempted"])
        self.assertEqual("http_429_refused_without_retry", roster["stopReason"])
        self.assertEqual(0, roster["eligibleCount"])


if __name__ == "__main__":
    unittest.main()
