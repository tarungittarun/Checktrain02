#!/usr/bin/env python3
"""Build a bounded roster of up to 20 scheduled trains corroborated by direct WIMT .in replies.

This is a campaign admission check, not an independent train-movement verifier. Timetable run-days
and times define the eligibility window; a single ordinary GET to the app's WIMT .in endpoint must
also echo the requested run date, both route endpoints, and aligned scheduled endpoint clocks, with
an update stamp fresh when the complete response arrives. No .org.in website/cache, cookies, browser
impersonation, retries, or response bodies are used.
"""
from __future__ import annotations

import argparse
import gzip
import json
import re
import sqlite3
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable
from zoneinfo import ZoneInfo

IST = ZoneInfo("Asia/Kolkata")
UTC = timezone.utc
ENDPOINT = "https://whereismytrain.in/cache/live_status"
USER_AGENT = "TrackTrain/1.0 (Android; selected WIMT source)"
MAX_RESPONSE_BYTES = 1_000_000
MAX_FRESH_AGE_SECONDS = 10 * 60
MAX_CAMPAIGN_PROBES = 60
PROBE_INTERVAL_SECONDS = 1.0
FUTURE_STAMP_TOLERANCE_SECONDS = 120
SCHEDULE_TOLERANCE_MINUTES = 5
KNOWN_TRAIN_ID_CONFLICTS = {"16221", "16228"}  # Explicitly flagged by the current ETL known-truth gate.
WEEKDAY_BITS = {0: 1, 1: 2, 2: 4, 3: 8, 4: 16, 5: 32, 6: 64}  # Sunday first, like the ETL.


class ProbeFailure(Exception):
    def __init__(self, code: str, *, status: int | None = None, response_bytes: int | None = None):
        super().__init__(code)
        self.code = code
        self.status = status
        self.response_bytes = response_bytes


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Keep the campaign's .in source identity explicit; never follow redirects."""

    def redirect_request(self, req, fp, code, msg, headers, new_url):
        return None


def parse_instant(value: str) -> datetime:
    """Parse an ISO stamp using the same no-zone=IST rule as WimtLiveProvider."""
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    parsed = datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=IST)
    return parsed.astimezone(UTC)


def epoch_day(value: date) -> int:
    return (value - date(1970, 1, 1)).days


def run_date_text(value: date) -> str:
    return value.strftime("%d-%m-%Y")


def station_code(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip().upper()
    return text if re.fullmatch(r"[A-Z0-9]{2,6}", text) else None


def first_nonblank(doc: dict[str, Any], *keys: str) -> str | None:
    for key in keys:
        value = doc.get(key)
        if value is None:
            continue
        text = str(value).strip()
        if text and text.lower() != "null":
            return text
    return None


def parse_clock(value: Any) -> int | None:
    if value is None:
        return None
    match = re.fullmatch(r"\s*(\d{1,2}):(\d{2})\s*", str(value))
    if not match:
        return None
    hour, minute = int(match.group(1)), int(match.group(2))
    if minute not in range(60) or hour > 47:
        return None
    return hour * 60 + minute


def align_clock(clock: int, anchor: int) -> int:
    """Place a source HH:mm on the timetable's unwrapped day axis."""
    base_day = anchor // 1440
    return min((clock + 1440 * day for day in range(base_day - 2, base_day + 3)),
               key=lambda value: abs(value - anchor))


def _open_sqlite(path: Path) -> tuple[sqlite3.Connection, tempfile.TemporaryDirectory[str] | None]:
    """Open either the generated raw DB or the repository's gzip-framed .dbgz asset."""
    with path.open("rb") as stream:
        magic = stream.read(2)
    if magic != b"\x1f\x8b":
        return sqlite3.connect(path), None
    temp = tempfile.TemporaryDirectory(prefix="campaign-refdb-")
    raw_path = Path(temp.name) / "refdb.db"
    with gzip.open(path, "rb") as source, raw_path.open("wb") as target:
        while chunk := source.read(1024 * 1024):
            target.write(chunk)
    return sqlite3.connect(raw_path), temp


def _weekday_bit(day: date) -> int:
    # Python Monday=0; ETL bit 0=Sunday.
    sunday_first = (day.weekday() + 1) % 7
    return WEEKDAY_BITS[sunday_first]


def _classify_window(candidate: dict[str, Any], now_utc: datetime) -> dict[str, Any] | None:
    now_local = now_utc.astimezone(IST)
    expected_date = datetime.strptime(candidate["runDate"], "%d-%m-%Y").date()
    if now_local.date() != expected_date:
        return None
    current_minute = now_local.hour * 60 + now_local.minute + now_local.second / 60.0
    departure = int(candidate["scheduledDepartureMinute"])
    arrival = int(candidate["scheduledArrivalMinute"])
    if departure <= current_minute <= arrival:
        category = "scheduled-active-now"
    elif current_minute < departure <= current_minute + 60:
        category = "scheduled-departure-within-60-minutes"
    else:
        return None
    updated = dict(candidate)
    updated["scheduleEligibility"] = category
    updated["minutesToScheduledDepartureAtSelection"] = round(departure - current_minute, 2)
    updated["scheduledMinutesRemainingAtSelection"] = round(arrival - current_minute, 2)
    return updated


def scheduled_candidates(db: sqlite3.Connection, now_utc: datetime) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Select only known run-day services scheduled active now or due within 60 minutes."""
    now_utc = now_utc.astimezone(UTC)
    now_ist = now_utc.astimezone(IST)
    run_day = now_ist.date()
    current_minute = now_ist.hour * 60 + now_ist.minute + now_ist.second / 60.0
    bit = _weekday_bit(run_day)
    db.row_factory = sqlite3.Row
    meta = {str(row[0]): str(row[1]) for row in db.execute("SELECT k,v FROM meta")}
    query = """
        SELECT t.train_no, t.name, t.src_code, t.dst_code, t.run_days, t.num_stops,
               first.arrival_min AS first_arrival, first.departure_min AS first_departure,
               last.arrival_min AS last_arrival, last.departure_min AS last_departure
        FROM trains t
        JOIN stops first ON first.train_no=t.train_no AND first.seq=1
        JOIN stops last ON last.train_no=t.train_no AND last.seq=t.num_stops
        WHERE t.run_days IS NOT NULL
        ORDER BY t.train_no
    """
    candidates: list[dict[str, Any]] = []
    candidate_groups: dict[str, list[dict[str, Any]]] = {}
    rejected: Counter[str] = Counter()
    for row in db.execute(query):
        train_no = str(row["train_no"]).strip()
        if not re.fullmatch(r"\d{4,5}", train_no):
            rejected["invalid_train_number"] += 1
            continue
        if train_no.startswith("0"):
            # The generated reference asset identifies these as retired, stale 0-prefixed IDs.
            # Do not spend a live preflight slot (or an emulator job) on a known-bad identifier.
            rejected["retired_zero_prefixed_train_number"] += 1
            continue
        if train_no in KNOWN_TRAIN_ID_CONFLICTS:
            rejected["known_timetable_train_id_conflict"] += 1
            continue
        if row["run_days"] is None or not (int(row["run_days"]) & bit):
            rejected["not_known_running_today"] += 1
            continue
        if int(row["num_stops"] or 0) < 2:
            rejected["route_too_short"] += 1
            continue
        departure = row["first_departure"] if row["first_departure"] is not None else row["first_arrival"]
        arrival = row["last_arrival"] if row["last_arrival"] is not None else row["last_departure"]
        if departure is None or arrival is None:
            rejected["schedule_endpoint_time_missing"] += 1
            continue
        departure, arrival = int(departure), int(arrival)
        if departure < 0 or departure >= 1440 or arrival < departure:
            rejected["invalid_schedule_window"] += 1
            continue
        src = station_code(row["src_code"])
        dst = station_code(row["dst_code"])
        if src is None or dst is None or src == dst:
            rejected["invalid_route_endpoints"] += 1
            continue
        candidate = {
            "trainNo": train_no,
            "trainName": str(row["name"] or "").strip(),
            "sourceStationCode": src,
            "destinationStationCode": dst,
            "runDate": run_date_text(run_day),
            "originEpochDay": epoch_day(run_day),
            "scheduledDepartureMinute": departure,
            "scheduledArrivalMinute": arrival,
        }
        candidate = _classify_window(candidate, now_utc)
        if candidate is not None:
            candidate_groups.setdefault(train_no, []).append(candidate)

    for train_no, rows in candidate_groups.items():
        signatures = {
            (item["sourceStationCode"], item["destinationStationCode"],
             item["scheduledDepartureMinute"], item["scheduledArrivalMinute"])
            for item in rows
        }
        if len(signatures) > 1:
            rejected["conflicting_timetable_candidate_rows"] += 1
            continue
        if len(rows) > 1:
            rejected["duplicate_timetable_candidate_train_number"] += len(rows) - 1
        candidates.append(rows[0])

    candidates.sort(key=lambda item: (
        0 if item["scheduleEligibility"] == "scheduled-active-now" else 1,
        -item["scheduledMinutesRemainingAtSelection"]
        if item["scheduleEligibility"] == "scheduled-active-now"
        else item["minutesToScheduledDepartureAtSelection"],
        item["trainNo"],
    ))
    dataset = {
        "sourceVintage": meta.get("source_vintage") or None,
        "timetableVintage": meta.get("timetable_vintage") or None,
        "timetableAgeDays": _int_or_none(meta.get("timetable_age_days")),
        "timetableFreshness": meta.get("timetable_freshness") or "unknown",
        "runDaysVintage": meta.get("run_days_vintage") or None,
        "dataTrust": meta.get("data_trust") or "unverified",
        "dataTruthProblems": meta.get("data_truth_problems") or "",
        "knownTrainCount": int(db.execute("SELECT COUNT(*) FROM trains").fetchone()[0]),
        "knownRunDayCandidates": len(candidates),
        "scheduleFilterRejections": dict(sorted(rejected.items())),
    }
    return candidates, dataset


def _int_or_none(value: str | None) -> int | None:
    try:
        return int(value) if value is not None and value != "" else None
    except (TypeError, ValueError):
        return None


def fetch_once(train_no: str, run_date: str, timeout_seconds: float = 8.0) -> tuple[int, bytes]:
    query = urllib.parse.urlencode({"train_no": train_no, "date": run_date, "lang": "en"})
    request = urllib.request.Request(
        ENDPOINT + "?" + query,
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
        method="GET",
    )
    opener = urllib.request.build_opener(_NoRedirect())
    try:
        with opener.open(request, timeout=timeout_seconds) as response:
            status = int(response.status)
            body = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as error:
        raise ProbeFailure(f"http_{error.code}", status=error.code) from None
    except Exception as error:  # A reachability result, never a retry loop.
        name = type(error).__name__.lower()
        raise ProbeFailure("transport_timeout" if "timeout" in name else "transport_error") from None
    if len(body) > MAX_RESPONSE_BYTES:
        raise ProbeFailure("response_too_large", status=status, response_bytes=len(body))
    return status, body


def validate_reply(
    candidate: dict[str, Any], status: int, body: bytes, now_utc: datetime,
) -> dict[str, Any]:
    if len(body) > MAX_RESPONSE_BYTES:
        raise ProbeFailure("response_too_large", status=status, response_bytes=len(body))
    if status != 200:
        raise ProbeFailure(f"http_{status}", status=status, response_bytes=len(body))
    try:
        doc = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise ProbeFailure("invalid_json", status=status, response_bytes=len(body)) from None
    if not isinstance(doc, dict):
        raise ProbeFailure("unexpected_response_shape", status=status, response_bytes=len(body))

    echoed_train = first_nonblank(doc, "train_no", "train_number", "trainNo")
    if echoed_train is not None and echoed_train != candidate["trainNo"]:
        raise ProbeFailure("train_number_mismatch", status=status, response_bytes=len(body))

    echoed_date = first_nonblank(doc, "start_date", "train_start_date")
    if echoed_date is None:
        raise ProbeFailure("run_date_not_echoed", status=status, response_bytes=len(body))
    parsed_date: date | None = None
    for fmt in ("%d-%m-%Y", "%Y-%m-%d"):
        try:
            parsed_date = datetime.strptime(echoed_date, fmt).date()
            break
        except ValueError:
            continue
    if parsed_date is None or parsed_date.isoformat() != datetime.strptime(
        candidate["runDate"], "%d-%m-%Y").date().isoformat():
        raise ProbeFailure("run_date_mismatch", status=status, response_bytes=len(body))

    source = station_code(first_nonblank(doc, "source_station_code", "source_code", "source_station"))
    destination = station_code(first_nonblank(
        doc, "destination_station_code", "destination_code", "destination_station"))
    if source is None or destination is None:
        raise ProbeFailure("route_endpoints_not_echoed", status=status, response_bytes=len(body))
    if (source, destination) != (candidate["sourceStationCode"], candidate["destinationStationCode"]):
        raise ProbeFailure("route_endpoints_mismatch", status=status, response_bytes=len(body))

    rows = doc.get("days_schedule")
    if not isinstance(rows, list) or not rows:
        raise ProbeFailure("schedule_rows_missing", status=status, response_bytes=len(body))
    source_row = next((row for row in rows if isinstance(row, dict) and
                       station_code(first_nonblank(row, "station_code")) ==
                       candidate["sourceStationCode"]), None)
    destination_row = next((row for row in reversed(rows) if isinstance(row, dict) and
                            station_code(first_nonblank(row, "station_code")) ==
                            candidate["destinationStationCode"]), None)
    source_clock = parse_clock(first_nonblank(source_row or {}, "sch_departure_time"))
    destination_clock = parse_clock(first_nonblank(destination_row or {}, "sch_arrival_time"))
    if source_clock is None or destination_clock is None:
        raise ProbeFailure("schedule_endpoint_times_not_echoed", status=status,
                           response_bytes=len(body))
    source_minute = align_clock(source_clock, candidate["scheduledDepartureMinute"])
    destination_minute = align_clock(destination_clock, candidate["scheduledArrivalMinute"])
    if (abs(source_minute - candidate["scheduledDepartureMinute"]) > SCHEDULE_TOLERANCE_MINUTES or
            abs(destination_minute - candidate["scheduledArrivalMinute"]) > SCHEDULE_TOLERANCE_MINUTES):
        raise ProbeFailure("schedule_endpoint_times_mismatch", status=status,
                           response_bytes=len(body))
    stamp_text = first_nonblank(doc, "lastUpdateIsoDate")
    if stamp_text is None:
        raise ProbeFailure("source_stamp_missing", status=status, response_bytes=len(body))
    try:
        stamp_utc = parse_instant(stamp_text)
    except (ValueError, OverflowError):
        raise ProbeFailure("source_stamp_invalid", status=status, response_bytes=len(body)) from None
    now_utc = now_utc.astimezone(UTC)
    age_seconds = (now_utc - stamp_utc).total_seconds()
    if age_seconds < 0 or stamp_utc > now_utc + timedelta(seconds=FUTURE_STAMP_TOLERANCE_SECONDS):
        raise ProbeFailure("source_stamp_future", status=status, response_bytes=len(body))
    if age_seconds > MAX_FRESH_AGE_SECONDS:
        raise ProbeFailure("source_stamp_stale", status=status, response_bytes=len(body))

    current_station = first_nonblank(doc, "curStn")
    if not current_station:
        current_station = next((first_nonblank(row, "station_code") for row in rows
                                if isinstance(row, dict) and row.get("curStn") is True), None)
    if candidate["scheduleEligibility"] == "scheduled-active-now" and not current_station:
        raise ProbeFailure("active_train_current_station_missing", status=status,
                           response_bytes=len(body))
    actual_rows = sum(
        1 for row in rows if isinstance(row, dict)
        and not str(row.get("source", "")).lower() == "eta"
        and any(row.get(key) not in (None, "", 0, "0") for key in (
            "actual_arrival_time", "actual_departure_time", "actual_arrival_tm",
            "actual_departure_tm", "actual_arrival_epoch", "actual_departure_epoch"))
    )
    return {
        "httpStatus": status,
        "responseBytes": len(body),
        "runDateMatched": True,
        "routeMatched": True,
        "freshSourceStamp": True,
        "sourceStampAgeSecondsAtProbe": round(age_seconds, 2),
        "sourceScheduleRowCount": len(rows),
        "sourceRowsWithActualFields": actual_rows,
        "sourceCurrentStationFieldPresent": bool(current_station),
        "sourceScheduledDepartureMinute": source_minute,
        "sourceScheduledArrivalMinute": destination_minute,
        "scheduleEndpointTimesMatched": True,
        "scheduleEndpointToleranceMinutes": SCHEDULE_TOLERANCE_MINUTES,
        "accepted": True,
    }


def build_roster(
    db_path: Path,
    now_utc: datetime,
    min_candidates: int = 20,
    max_probes: int = MAX_CAMPAIGN_PROBES,
    fetch: Callable[[str, str], tuple[int, bytes]] = fetch_once,
    sleep: Callable[[float], None] = time.sleep,
) -> dict[str, Any]:
    db, temp = _open_sqlite(db_path)
    try:
        candidates, dataset = scheduled_candidates(db, now_utc)
    finally:
        db.close()
        if temp is not None:
            temp.cleanup()

    accepted: list[dict[str, Any]] = []
    rejection_counts: Counter[str] = Counter()
    probes = 0
    consecutive_transport_failures = 0
    stop_reason: str | None = None
    timetable_age = dataset.get("timetableAgeDays")
    timetable_is_recent = (
        dataset.get("timetableFreshness") == "recent"
        and isinstance(timetable_age, int)
        and not isinstance(timetable_age, bool)
        and 0 <= timetable_age <= 90
    )
    if not timetable_is_recent:
        rejection_counts["timetable_vintage_not_recent"] += 1
        stop_reason = "timetable_vintage_not_recent"
    live_fetch = fetch is fetch_once
    for candidate in (candidates if timetable_is_recent else []):
        if len(accepted) >= min_candidates or probes >= max_probes:
            break
        request_time = datetime.now(UTC) if live_fetch else now_utc
        current_candidate = _classify_window(candidate, request_time)
        if current_candidate is None:
            rejection_counts["eligibility_expired_during_scan"] += 1
            continue
        if probes:
            # One ordinary source GET at a time; no burst scan, retry, or challenge evasion.
            sleep(PROBE_INTERVAL_SECONDS)
            request_time = datetime.now(UTC) if live_fetch else now_utc
            current_candidate = _classify_window(candidate, request_time)
            if current_candidate is None:
                rejection_counts["eligibility_expired_during_scan"] += 1
                continue

        probes += 1
        try:
            status, body = fetch(current_candidate["trainNo"], current_candidate["runDate"])
            response_time = datetime.now(UTC) if live_fetch else now_utc
            after_request_candidate = _classify_window(current_candidate, response_time)
            if after_request_candidate is None:
                rejection_counts["eligibility_expired_during_probe"] += 1
                continue
            evidence = validate_reply(after_request_candidate, status, body, response_time)
        except ProbeFailure as error:
            rejection_counts[error.code] += 1
            if error.code in {"http_403", "http_429"}:
                stop_reason = error.code + "_refused_without_retry"
                break
            if error.status is not None and 300 <= error.status < 400:
                stop_reason = "source_redirect_refused_without_following"
                break
            if error.code.startswith("transport_") or error.code.startswith("http_5"):
                consecutive_transport_failures += 1
                if consecutive_transport_failures >= 3:
                    stop_reason = "three_consecutive_transport_or_5xx_failures"
                    break
            else:
                consecutive_transport_failures = 0
            print(f"roster probe {probes}: rejected train {current_candidate['trainNo']} ({error.code})",
                  file=sys.stderr, flush=True)
            continue
        except Exception as error:  # Keep a bad script/probe result visible and bounded.
            rejection_counts["unexpected_probe_error"] += 1
            print(f"roster probe {probes}: unexpected {type(error).__name__}", file=sys.stderr, flush=True)
            consecutive_transport_failures += 1
            if consecutive_transport_failures >= 3:
                stop_reason = "three_consecutive_probe_errors"
                break
            continue

        consecutive_transport_failures = 0
        entry = dict(after_request_candidate)
        entry["slot"] = len(accepted) + 1
        entry["preflightCheckedAtUtc"] = response_time.isoformat(timespec="seconds").replace("+00:00", "Z")
        entry["preflightSourceEvidence"] = evidence
        accepted.append(entry)
        print(f"roster probe {probes}: accepted slot {entry['slot']} train {entry['trainNo']} "
              f"({entry['scheduleEligibility']})", flush=True)

    generated = datetime.now(UTC)
    status = "READY" if len(accepted) == min_candidates else "INSUFFICIENT_ELIGIBLE_CANDIDATES"
    return {
        "schemaVersion": 1,
        "campaign": "wimt-20-train-45-minute-emulator-campaign",
        "status": status,
        "requiredSlots": min_candidates,
        "eligibleCount": len(accepted),
        "probesAttempted": probes,
        "maxProbes": max_probes,
        "stopReason": stop_reason,
        "generatedAtUtc": generated.isoformat(timespec="seconds").replace("+00:00", "Z"),
        "generatedAtIst": generated.astimezone(IST).isoformat(timespec="seconds"),
        "timetable": dataset,
        "eligibilityRule": (
            "The generated timetable's known run-day bitmap and schedule must show the train "
            "scheduled active now or due within the next 60 minutes. A single direct WIMT .in "
            "response must echo the requested run date and both route endpoints, match both scheduled "
            "endpoint clocks within five minutes, and have a source update stamp no more than 10 minutes "
            "old when the response body arrives. This is source/timetable corroboration, not independent "
            "verification of train movement or GPS."
        ),
        "sourceIdentity": {
            "name": "WIMT .in direct /cache/live_status response",
            "host": "whereismytrain.in",
            "distinctFrom": ["Where Is My Train website (.org.in)", "WIMT web cache",
                             "eRail", "Google WIMT Android app"],
            "responseBodiesStored": False,
            "retries": 0,
        },
        "rejectionCounts": dict(sorted(rejection_counts.items())),
        "assignments": accepted,
    }


def parse_now(value: str | None) -> datetime:
    if value is None:
        return datetime.now(UTC)
    return parse_instant(value)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True, type=Path, help="generated SQLite refdb or gzip-framed .dbgz")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--min-candidates", type=int, default=20)
    parser.add_argument("--max-probes", type=int, default=MAX_CAMPAIGN_PROBES)
    parser.add_argument("--now-utc", help="test seam: ISO timestamp; omitted means current UTC time")
    args = parser.parse_args()
    if args.min_candidates != 20:
        parser.error("this campaign requires exactly 20 distinct assignments")
    if not 1 <= args.max_probes <= MAX_CAMPAIGN_PROBES:
        parser.error(f"--max-probes must be from 1 through {MAX_CAMPAIGN_PROBES}")
    if not args.db.is_file():
        parser.error(f"reference DB not found: {args.db}")
    roster = build_roster(args.db, parse_now(args.now_utc), args.min_candidates, args.max_probes)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(roster, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({
        "status": roster["status"],
        "requiredSlots": roster["requiredSlots"],
        "eligibleCount": roster["eligibleCount"],
        "probesAttempted": roster["probesAttempted"],
        "stopReason": roster["stopReason"],
        "output": str(args.output),
    }, sort_keys=True))
    # Insufficient candidates are evidence, not a tooling crash. The 20-slot workflow will
    # report unavailable slots and run only genuinely assigned, distinct trains.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
