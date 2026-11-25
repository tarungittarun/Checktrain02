#!/usr/bin/env python3
"""Cross-check the emulator runner's exit verdict against the collected in-app evidence."""
from __future__ import annotations

import argparse
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
import re
from typing import Any

EXPECTED_MINUTES = list(range(46))
SCREEN_MINUTES = (0, 15, 30, 45)
MIN_DURATION_MS = 45 * 60 * 1000
MIN_NEW_RECEIPTS = 43
EXPECTED_SOURCE_FRAGMENT = "Selected NTES · official"
APP_PACKAGE = "in.opentrack.india"
ELIGIBILITY_CATEGORIES = {
    "scheduled-active-now",
    "scheduled-departure-within-60-minutes",
}


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read {path}: {type(error).__name__}: {error}") from None
    if not isinstance(value, dict):
        raise ValueError(f"{path} JSON root is not an object")
    return value


def load_observations(path: Path) -> tuple[list[dict[str, Any]], list[str]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        return [], [f"cannot read observations: {type(error).__name__}: {error}"]
    observations: list[dict[str, Any]] = []
    errors: list[str] = []
    for index, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            errors.append(f"observation line {index} is malformed JSON")
            continue
        if not isinstance(value, dict):
            errors.append(f"observation line {index} is not an object")
            continue
        observations.append(value)
    return observations, errors


def _parse_instant(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _is_count(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _ntes_event_at(description: Any, run_date: Any) -> datetime | None:
    if not isinstance(description, str) or not isinstance(run_date, str):
        return None
    match = re.search(r"Last NTES event at .+?,\s*(\d{1,2}):(\d{2})(?:\s*\(\+(\d+)d\))?", description)
    if not match:
        return None
    try:
        ist = timezone(timedelta(hours=5, minutes=30))
        start = datetime.strptime(run_date, "%d-%m-%Y").replace(tzinfo=ist).astimezone(timezone.utc)
        hour, minute = int(match.group(1)), int(match.group(2))
        if hour > 23 or minute > 59:
            return None
        day_offset = int(match.group(3) or 0)
        return start + timedelta(days=day_offset, hours=hour, minutes=minute)
    except (ValueError, OverflowError):
        return None


def _ntes_event_is_fresh(description: Any, run_date: Any, sampled_at: Any) -> bool:
    event_at = _ntes_event_at(description, run_date)
    sample_at = _parse_instant(sampled_at)
    if event_at is None or sample_at is None:
        return False
    age = (sample_at - event_at).total_seconds()
    return -120 <= age <= 120 * 60


def verify(
    result: dict[str, Any],
    observations: list[dict[str, Any]],
    instrumentation: str,
    instrumentation_exit_code: int,
    evidence_dir: Path,
    assignment: dict[str, Any],
    eligibility_before: dict[str, Any],
    eligibility_after: dict[str, Any],
    observation_errors: list[str] | None = None,
) -> list[str]:
    errors = list(observation_errors or [])
    if instrumentation_exit_code != 0:
        errors.append(f"Android instrumentation exited with {instrumentation_exit_code}")
    if "OK (1 test)" not in instrumentation:
        errors.append("instrumentation summary did not contain OK (1 test)")
    if "FAILURES!!!" in instrumentation:
        errors.append("instrumentation output contains FAILURES!!!")
    if result.get("passed") is not True:
        errors.append("the in-app result did not pass its acceptance checks")
    if result.get("flowPassed") is not True:
        errors.append("the installed-app train-selection flow did not pass")

    assigned_slot = assignment.get("slot")
    assigned_train = assignment.get("trainNo")
    assigned_date = assignment.get("runDate")
    for key, expected in (("slot", assigned_slot), ("trainNo", assigned_train), ("runDate", assigned_date)):
        if result.get(key) != expected:
            errors.append(f"in-app result {key} does not match its roster assignment")

    duration = result.get("durationMs")
    if not _is_count(duration) or duration < MIN_DURATION_MS:
        errors.append(f"in-app duration was under 45 minutes: {duration!r} ms")
    if result.get("observationCount") != 46 or result.get("expectedObservationCount") != 46:
        errors.append("in-app result did not report exactly 46 expected observations")
    if result.get("screenPresentSamples") != 46:
        errors.append("not all 46 observations reported the app screen present")
    if result.get("autoOnAtEverySample") is not True or result.get("autoEnabledAtStart") is not True:
        errors.append("Auto was not confirmed enabled at the start and every sample")
    if result.get("eventTimeQualifiedAtEverySample") is not True:
        errors.append("a selected NTES event clock was not qualified fresh at every sample")
    if result.get("autoOffAtEnd") is not True:
        errors.append("Auto was not confirmed off at the end")
    if result.get("deviceStartEligible") is not True:
        errors.append("in-app device-start schedule eligibility was not confirmed")
    result_schedule_category = result.get("deviceStartScheduleEligibility")
    if not isinstance(result_schedule_category, str) or \
            result_schedule_category not in ELIGIBILITY_CATEGORIES:
        errors.append("in-app schedule category was not active or due within 60 minutes")

    receipts = result.get("minuteRefreshReceipts")
    missed = result.get("missedOrUnchangedReceiptSamples")
    if not _is_count(receipts) or receipts < MIN_NEW_RECEIPTS:
        errors.append(f"minute refresh receipts were below {MIN_NEW_RECEIPTS}: {receipts!r}")
    if not _is_count(missed) or missed > 45 - MIN_NEW_RECEIPTS:
        errors.append(f"missed/unchanged receipt count exceeded {45 - MIN_NEW_RECEIPTS}: {missed!r}")
    if _is_count(receipts) and _is_count(missed) and receipts + missed != 45:
        errors.append("reported refresh receipt and missed-sample counts do not sum to 45")

    for key in ("selectedSourceAtStart", "selectedSourceAtEnd"):
        source = result.get(key)
        if not isinstance(source, str) or EXPECTED_SOURCE_FRAGMENT.casefold() not in source.casefold():
            errors.append(f"{key} did not identify {EXPECTED_SOURCE_FRAGMENT!r}")

    start = _parse_instant(result.get("startedAtUtc"))
    end = _parse_instant(result.get("endedAtUtc"))
    if start is None or end is None or end <= start:
        errors.append("in-app observation start/end timestamps are missing or invalid")
    elif (end - start).total_seconds() * 1000 < MIN_DURATION_MS:
        errors.append("UTC observation timestamps span less than 45 minutes")

    for label, gate in (("before-emulator", eligibility_before), ("after-emulator-boot", eligibility_after)):
        if gate.get("eligible") is not True:
            errors.append(f"host schedule/source eligibility was not confirmed {label}")
        for key, expected in (("slot", assigned_slot), ("trainNo", assigned_train), ("runDate", assigned_date)):
            if gate.get(key) != expected:
                errors.append(f"host eligibility {label} {key} does not match its roster assignment")
        gate_category = gate.get("scheduleEligibilityAtStart")
        if not isinstance(gate_category, str) or gate_category not in ELIGIBILITY_CATEGORIES:
            errors.append(f"host eligibility {label} schedule was not active or due within 60 minutes")
        gate_age = gate.get("preflightSourceStampAgeSecondsAtStart")
        if not isinstance(gate_age, (int, float)) or isinstance(gate_age, bool) or \
                gate_age < 0 or gate_age > 10 * 60:
            errors.append(f"host eligibility {label} did not confirm a fresh WIMT .in stamp")
        if _parse_instant(gate.get("checkedAtUtc")) is None:
            errors.append(f"host eligibility {label} timestamp is missing or invalid")

    minute_values = [item.get("minute") for item in observations]
    numeric_minutes = [
        minute for minute in minute_values
        if isinstance(minute, int) and not isinstance(minute, bool)
    ]
    if len(observations) != 46 or len(numeric_minutes) != len(observations) or \
            sorted(numeric_minutes) != EXPECTED_MINUTES:
        errors.append("observations.jsonl did not contain exactly one sample for each minute 0..45")
    observed_minute_set = set(numeric_minutes)
    for missing in sorted(set(EXPECTED_MINUTES) - observed_minute_set):
        errors.append(f"minute {missing} is missing from observations.jsonl")

    new_receipts = 0
    for item in observations:
        minute = item.get("minute")
        if not isinstance(minute, int) or isinstance(minute, bool) or minute not in EXPECTED_MINUTES:
            continue
        if item.get("screenPresent") is not True:
            errors.append(f"minute {minute} did not report the app screen present")
        if item.get("autoEnabled") is not True:
            errors.append(f"minute {minute} did not report Auto enabled")
        if not isinstance(item.get("sampledAtIst"), str) or \
                _parse_instant(item.get("sampledAtIst")) is None:
            errors.append(f"minute {minute} has no valid capture timestamp")
        if str(item.get("trainNo") or "") != str(assigned_train):
            errors.append(f"minute {minute} train number does not match the roster assignment")
        if str(item.get("runDate") or "") != str(assigned_date):
            errors.append(f"minute {minute} run date does not match the roster assignment")
        if not str(item.get("route") or "").strip():
            errors.append(f"minute {minute} has no visible route")
        if item.get("eventTimeQualified") is not True:
            errors.append(f"minute {minute} NTES source event clock was missing, stale, future, or unqualified")
        event_description = item.get("eventDescription")
        if not isinstance(event_description, str) or "Last NTES event at " not in event_description:
            errors.append(f"minute {minute} has no source-attributed NTES event-time description")
        elif not _ntes_event_is_fresh(event_description, assigned_date, item.get("sampledAtIst")):
            errors.append(f"minute {minute} NTES event clock is not within 2 hours of its capture time")
        source_description = item.get("sourceDescription") or item.get("sourceLabel")
        if not isinstance(source_description, str) or \
                EXPECTED_SOURCE_FRAGMENT.casefold() not in source_description.casefold():
            errors.append(f"minute {minute} did not identify the selected NTES · official source")
        if 1 <= minute <= 45 and item.get("newReceiptSincePrevious") is True:
            new_receipts += 1
    if new_receipts < MIN_NEW_RECEIPTS:
        errors.append(f"observations.jsonl contains only {new_receipts} new minute receipts")
    if _is_count(receipts) and receipts != new_receipts:
        errors.append(f"summary receipt count {receipts} disagrees with observations {new_receipts}")

    screenshots = result.get("verifiedAppScreenshots")
    if not isinstance(screenshots, list):
        screenshots = []
    screenshot_set = {str(item) for item in screenshots}
    for minute in SCREEN_MINUTES:
        name = f"campaign-minute-{minute:02d}"
        rel_png = f"screens/{name}.png"
        rel_xml = f"screens/{name}.xml"
        if rel_png not in screenshot_set:
            errors.append(f"app result omitted screenshot reference {rel_png}")
        png = evidence_dir / rel_png
        try:
            data = png.read_bytes()
        except OSError:
            data = b""
        valid_png = (
            len(data) > 1000
            and data.startswith(b"\x89PNG\r\n\x1a\n")
            and len(data) >= 24
            and int.from_bytes(data[16:20], "big") > 0
            and int.from_bytes(data[20:24], "big") > 0
        )
        if not valid_png:
            errors.append(f"screenshot {rel_png} is missing, too small, or not a valid-sized PNG")
        xml = evidence_dir / rel_xml
        try:
            xml_text = xml.read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            xml_text = ""
        if len(xml_text) <= 100 or f'package="{APP_PACKAGE}"' not in xml_text:
            errors.append(f"screenshot hierarchy {rel_xml} is missing or not from {APP_PACKAGE}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--result", type=Path, required=True)
    parser.add_argument("--observations", type=Path, required=True)
    parser.add_argument("--instrumentation", type=Path, required=True)
    parser.add_argument("--instrumentation-exit-code", type=int, required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--assignment", type=Path, required=True)
    parser.add_argument("--eligibility-before", type=Path, required=True)
    parser.add_argument("--eligibility-after", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = load_json(args.result)
        assignment = load_json(args.assignment)
        eligibility_before = load_json(args.eligibility_before)
        eligibility_after = load_json(args.eligibility_after)
    except ValueError as error:
        print(str(error))
        return 1
    observations, observation_errors = load_observations(args.observations)
    try:
        instrumentation = args.instrumentation.read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        instrumentation = ""
        observation_errors.append(f"cannot read instrumentation output: {type(error).__name__}: {error}")
    errors = verify(
        result, observations, instrumentation, args.instrumentation_exit_code,
        args.evidence_dir, assignment, eligibility_before, eligibility_after, observation_errors,
    )
    if errors:
        print("Campaign runner acceptance failed:")
        for error in errors:
            print(f"- {error}")
        return 1
    print(
        "Campaign runner acceptance passed: "
        f"train={result.get('trainNo')} date={result.get('runDate')} "
        f"durationMs={result.get('durationMs')} observations=46 receipts={result.get('minuteRefreshReceipts')}/45"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
