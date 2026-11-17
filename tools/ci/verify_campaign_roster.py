#!/usr/bin/env python3
"""Fail closed unless a fresh, source-identified 20-train roster is safe to launch."""
from __future__ import annotations

import argparse
import json
import math
import re
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

IST = ZoneInfo("Asia/Kolkata")
UTC = timezone.utc
REQUIRED_SLOTS = 20
MAX_TIMETABLE_AGE_DAYS = 90
MAX_SOURCE_AGE_SECONDS = 10 * 60
ELIGIBILITY_CATEGORIES = {
    "scheduled-active-now",
    "scheduled-departure-within-60-minutes",
}
SOURCE_IDENTITY = "WIMT .in direct /cache/live_status response"


def parse_instant(value: Any) -> datetime:
    if not isinstance(value, str) or not value.strip():
        raise ValueError("timestamp missing")
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    parsed = datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)


def _integer(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def verify_roster(roster: Any, now_utc: datetime | None = None) -> list[str]:
    """Return every admission failure; never infer or repair an assignment."""
    errors: list[str] = []
    if not isinstance(roster, dict):
        return ["roster root is not an object"]
    now = now_utc or datetime.now(UTC)
    if now.tzinfo is None:
        now = now.replace(tzinfo=UTC)
    now = now.astimezone(UTC)
    now_ist = now.astimezone(IST)

    if roster.get("status") != "READY":
        errors.append(f"roster status is not READY: {roster.get('status')!r}")
    if roster.get("requiredSlots") != REQUIRED_SLOTS:
        errors.append("roster does not require exactly 20 slots")
    if roster.get("eligibleCount") != REQUIRED_SLOTS:
        errors.append(f"roster eligibleCount is not 20: {roster.get('eligibleCount')!r}")

    timetable = roster.get("timetable")
    if not isinstance(timetable, dict):
        timetable = {}
        errors.append("timetable provenance is missing")
    age_days = timetable.get("timetableAgeDays")
    if timetable.get("timetableFreshness") != "recent" or not _integer(age_days) or \
            not 0 <= age_days <= MAX_TIMETABLE_AGE_DAYS:
        errors.append(
            "timetable source-history vintage is not recent (requires recent status and age 0..90 days)"
        )

    source_identity = roster.get("sourceIdentity")
    if not isinstance(source_identity, dict) or \
            source_identity.get("name") != SOURCE_IDENTITY or \
            source_identity.get("host") != "whereismytrain.in" or \
            source_identity.get("responseBodiesStored") is not False or \
            source_identity.get("retries") != 0:
        errors.append("direct WIMT .in source identity/one-request boundary is not confirmed")

    assignments = roster.get("assignments")
    if not isinstance(assignments, list):
        assignments = []
        errors.append("assignments is not a list")
    if len(assignments) != REQUIRED_SLOTS:
        errors.append(f"roster has {len(assignments)} assignments, not 20")

    seen_slots: set[int] = set()
    seen_trains: set[str] = set()
    seen_dates: set[date] = set()
    for index, item in enumerate(assignments, 1):
        label = f"assignment {index}"
        if not isinstance(item, dict):
            errors.append(f"{label} is not an object")
            continue
        slot = item.get("slot")
        if not _integer(slot) or slot not in range(1, REQUIRED_SLOTS + 1):
            errors.append(f"{label} has an invalid slot")
        elif slot in seen_slots:
            errors.append(f"duplicate matrix slot {slot}")
        else:
            seen_slots.add(slot)

        train_no = item.get("trainNo")
        if not isinstance(train_no, str) or not re.fullmatch(r"[0-9]{4,5}", train_no) or \
                train_no.startswith("0"):
            errors.append(f"{label} has an invalid or retired train number")
        elif train_no in seen_trains:
            errors.append(f"duplicate train number {train_no}")
        else:
            seen_trains.add(train_no)

        run_date: date | None = None
        try:
            run_date = datetime.strptime(item.get("runDate", ""), "%d-%m-%Y").date()
            seen_dates.add(run_date)
        except (TypeError, ValueError):
            errors.append(f"{label} has an invalid run date")

        schedule_category = item.get("scheduleEligibility")
        if not isinstance(schedule_category, str) or schedule_category not in ELIGIBILITY_CATEGORIES:
            errors.append(f"{label} is not scheduled active now or due within 60 minutes")
        departure = item.get("scheduledDepartureMinute")
        arrival = item.get("scheduledArrivalMinute")
        if not _integer(departure) or not _integer(arrival) or \
                not 0 <= departure < 1440 or arrival < departure:
            errors.append(f"{label} has invalid scheduled endpoint minutes")
        elif run_date == now_ist.date():
            current_minute = now_ist.hour * 60 + now_ist.minute + now_ist.second / 60
            still_eligible = (
                departure <= current_minute <= arrival
                or current_minute < departure <= current_minute + 60
            )
            if not still_eligible:
                errors.append(f"{label} schedule window expired before roster admission")

        try:
            checked_at = parse_instant(item.get("preflightCheckedAtUtc"))
            age_since_probe = (now - checked_at).total_seconds()
            if age_since_probe < -120:
                errors.append(f"{label} preflight timestamp is in the future")
            evidence = item.get("preflightSourceEvidence")
            if not isinstance(evidence, dict):
                raise ValueError("source evidence missing")
            source_age = evidence.get("sourceStampAgeSecondsAtProbe")
            if not isinstance(source_age, (int, float)) or isinstance(source_age, bool) or \
                    not math.isfinite(source_age) or source_age < 0:
                errors.append(f"{label} source-stamp age is invalid")
            elif source_age + max(0.0, age_since_probe) > MAX_SOURCE_AGE_SECONDS:
                errors.append(f"{label} WIMT .in source stamp has expired")
            for key in (
                "accepted", "runDateMatched", "routeMatched",
                "scheduleEndpointTimesMatched", "freshSourceStamp",
            ):
                if evidence.get(key) is not True:
                    errors.append(f"{label} direct-source check {key} is not confirmed")
            if evidence.get("httpStatus") != 200:
                errors.append(f"{label} direct-source HTTP status is not 200")
            actual_field_rows = evidence.get("sourceRowsWithActualFields", 0)
            if not _integer(actual_field_rows) or actual_field_rows < 0:
                errors.append(f"{label} direct-source row count is invalid")
            if run_date is not None and checked_at.astimezone(IST).date() != run_date:
                errors.append(f"{label} run date does not match its preflight timestamp")
        except (TypeError, ValueError, OverflowError):
            errors.append(f"{label} direct-source preflight timestamp/evidence is invalid")

    if seen_slots != set(range(1, REQUIRED_SLOTS + 1)):
        errors.append("roster does not contain each matrix slot 1..20 exactly once")
    if len(seen_trains) != REQUIRED_SLOTS:
        errors.append("roster does not contain 20 distinct train numbers")
    if len(seen_dates) > 1:
        errors.append("roster assignments span multiple run dates")
    if seen_dates and seen_dates != {now_ist.date()}:
        errors.append("roster run date is not the current Asia/Kolkata date")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--roster", type=Path, required=True)
    parser.add_argument("--output", type=Path, help="optional JSON validation summary")
    args = parser.parse_args()
    try:
        roster = json.loads(args.roster.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        roster = None
        read_error = f"cannot read roster: {type(error).__name__}: {error}"
    else:
        read_error = None
    errors = [read_error] if read_error else verify_roster(roster)
    result = {
        "valid": not errors,
        "validatedEligibleCount": REQUIRED_SLOTS if not errors else 0,
        "assignmentCount": len(roster.get("assignments", [])) if isinstance(roster, dict)
            and isinstance(roster.get("assignments"), list) else 0,
        "errors": errors,
    }
    text = json.dumps(result, indent=2, sort_keys=True) + "\n"
    print(text, end="")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
