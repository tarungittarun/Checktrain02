#!/usr/bin/env python3
"""Recheck a roster assignment's timetable window and preflight-feed freshness at device start."""
from __future__ import annotations

import argparse
import json
import sys
from datetime import date, datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

IST = ZoneInfo("Asia/Kolkata")
UTC = timezone.utc
MAX_PREFLIGHT_SOURCE_AGE_SECONDS = 10 * 60


def parse_instant(value: str) -> datetime:
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    result = datetime.fromisoformat(text)
    if result.tzinfo is None:
        result = result.replace(tzinfo=UTC)
    return result.astimezone(UTC)


def check_assignment(assignment: dict, now_utc: datetime) -> dict:
    now_utc = now_utc.astimezone(UTC)
    now_ist = now_utc.astimezone(IST)
    expected_date = datetime.strptime(assignment["runDate"], "%d-%m-%Y").date()
    reasons: list[str] = []
    if now_ist.date() != expected_date:
        reasons.append("run_date_is_no_longer_today")
    departure = int(assignment["scheduledDepartureMinute"])
    arrival = int(assignment["scheduledArrivalMinute"])
    current_minute = now_ist.hour * 60 + now_ist.minute + now_ist.second / 60.0
    if departure <= current_minute <= arrival:
        category = "scheduled-active-now"
    elif current_minute < departure <= current_minute + 60:
        category = "scheduled-departure-within-60-minutes"
    else:
        category = "outside_eligibility_window"
        reasons.append("scheduled_train_not_active_or_due_within_60_minutes")

    try:
        preflight_at = parse_instant(assignment["preflightCheckedAtUtc"])
        reported_age = float(assignment["preflightSourceEvidence"]["sourceStampAgeSecondsAtProbe"])
        source_age_now = (now_utc - preflight_at).total_seconds() + reported_age
        if source_age_now < 0 or source_age_now > MAX_PREFLIGHT_SOURCE_AGE_SECONDS:
            reasons.append("preflight_wimt_source_stamp_not_fresh_at_device_start")
    except (KeyError, TypeError, ValueError, OverflowError):
        source_age_now = None
        reasons.append("preflight_source_stamp_age_missing")

    source = assignment.get("preflightSourceEvidence") or {}
    for key in ("accepted", "runDateMatched", "routeMatched", "scheduleEndpointTimesMatched", "freshSourceStamp"):
        if source.get(key) is not True:
            reasons.append("preflight_" + key + "_not_confirmed")

    return {
        "schemaVersion": 1,
        "slot": assignment.get("slot"),
        "trainNo": assignment.get("trainNo"),
        "runDate": assignment.get("runDate"),
        "checkedAtUtc": now_utc.isoformat(timespec="seconds").replace("+00:00", "Z"),
        "checkedAtIst": now_ist.isoformat(timespec="seconds"),
        "scheduleEligibilityAtStart": category,
        "minutesToScheduledDepartureAtStart": round(departure - current_minute, 2),
        "scheduledMinutesRemainingAtStart": round(arrival - current_minute, 2),
        "preflightSourceStampAgeSecondsAtStart": round(source_age_now, 2)
            if source_age_now is not None else None,
        "eligible": not reasons,
        "reasons": reasons,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assignment", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--now-utc", help="test seam: ISO timestamp; omitted means current UTC time")
    args = parser.parse_args()
    try:
        assignment = json.loads(args.assignment.read_text(encoding="utf-8"))
        now = parse_instant(args.now_utc) if args.now_utc else datetime.now(UTC)
        result = check_assignment(assignment, now)
    except Exception as error:
        result = {
            "schemaVersion": 1,
            "eligible": False,
            "reasons": ["assignment_validation_error_" + type(error).__name__],
        }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, sort_keys=True))
    return 0 if result.get("eligible") is True else 2


if __name__ == "__main__":
    raise SystemExit(main())
