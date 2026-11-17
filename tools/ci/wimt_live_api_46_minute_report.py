#!/usr/bin/env python3
"""Build a bounded report for one installed-APK, 46-minute WIMT .in observation.

The report counts app receipts and current-station claims; it does not turn the source's
claims into independent proof of train movement, GPS, or railway actuals.
"""
from __future__ import annotations

import argparse
import json
import re
import struct
import zlib
from pathlib import Path
from typing import Any

REQUIRED_MINUTES = 46
REQUIRED_CHECKPOINTS = tuple(range(5, 46, 5))
PROVENANCE = (
    "The observation used the installed APK's selected WIMT .in live-status source and its "
    "one-minute Auto refresh. A fresh source-update stamp is not event time; the endpoint may "
    "be server-cached. Current-station fields are WIMT source claims, not independently verified "
    "railway movement, GPS, NTES actuals, or provider permission. WIMT .org.in, the separate "
    "WIMT web-cache path, NTES, eRail, and the Google WIMT Android app are not merged into this result."
)


def fields(line: str) -> dict[str, str]:
    return dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)=([^\s]+)", line))


def number(data: dict[str, str], key: str, default: int = 0) -> int:
    try:
        return int(data.get(key, str(default)))
    except (TypeError, ValueError, OverflowError):
        return default


def truth(data: dict[str, str], key: str) -> bool:
    return data.get(key, "").lower() == "true"


def valid_png(path: Path) -> bool:
    try:
        data = path.read_bytes()
        if len(data) <= 1_000 or data[:8] != b"\x89PNG\r\n\x1a\n":
            return False
        offset = 8
        width = height = 0
        saw_idat = saw_iend = False
        first = True
        while offset + 12 <= len(data):
            length = struct.unpack(">I", data[offset:offset + 4])[0]
            kind = data[offset + 4:offset + 8]
            end = offset + 8 + length
            if end + 4 > len(data):
                return False
            payload = data[offset + 8:end]
            expected_crc = struct.unpack(">I", data[end:end + 4])[0]
            if zlib.crc32(kind + payload) & 0xFFFFFFFF != expected_crc:
                return False
            if first:
                if kind != b"IHDR" or length != 13:
                    return False
                width, height = struct.unpack(">II", payload[:8])
                first = False
            if kind == b"IDAT":
                saw_idat = True
            if kind == b"IEND":
                if length != 0:
                    return False
                saw_iend = True
                offset = end + 4
                break
            offset = end + 4
        return (not first and width > 0 and height > 0 and saw_idat and saw_iend
                and offset == len(data))
    except (OSError, struct.error, OverflowError):
        return False


def valid_app_hierarchy(path: Path) -> bool:
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
        return len(text) > 100 and 'package="in.opentrack.india"' in text
    except OSError:
        return False


def _strict_detail(item: dict[str, str], *, require_station: bool) -> bool:
    station_claim = item.get("currentStationClaim", "").strip()
    station_ok = bool(station_claim) and station_claim.lower() != "unknown"
    return (
        item.get("httpStatus") == "200"
        and truth(item, "runDateMatched")
        and truth(item, "routeMatched")
        and truth(item, "fresh")
        and truth(item, "freshnessAccurate")
        and number(item, "rows") > 0
        and number(item, "cells") > 0
        and truth(item, "selected")
        and truth(item, "disclosed")
        and (station_ok or not require_station)
    )


def build_report(evidence: Path) -> dict[str, Any]:
    trace_path = evidence / "soak-trace.txt"
    trace = trace_path.read_text(encoding="utf-8", errors="replace") if trace_path.is_file() else ""
    lines = trace.splitlines()
    train_line = next((line for line in lines if "WIMT_SOAK_TRAIN " in line), "")
    initial_line = next((line for line in lines if "WIMT_SOAK_INITIAL " in line), "")
    auto_line = next((line for line in lines if "WIMT_SOAK_AUTO_ENABLE " in line), "")
    started_line = next((line for line in lines if "WIMT_SOAK_STARTED " in line), "")
    summary_line = next((line for line in reversed(lines) if "WIMT_SOAK_SUMMARY " in line), "")
    train = fields(train_line)
    initial = fields(initial_line)
    auto = fields(auto_line)
    started = fields(started_line)
    summary = fields(summary_line)
    require_station = truth(started, "requireCurrentStation") or truth(summary, "currentStationRequired")

    samples = [fields(line) for line in lines if "WIMT_SOAK_SAMPLE " in line]
    samples.sort(key=lambda item: number(item, "minute", -1))
    minute_numbers = [number(item, "minute", -1) for item in samples]
    samples_by_minute = {number(item, "minute", -1): item for item in samples}
    expected_sample_minutes = set(range(REQUIRED_MINUTES + 1))
    sample_minutes_ok = (
        len(minute_numbers) == len(set(minute_numbers))
        and expected_sample_minutes.issubset(set(minute_numbers))
    )
    minute_samples = [samples_by_minute.get(minute, {})
                      for minute in range(REQUIRED_MINUTES + 1)]
    receipt_samples_ok = bool(minute_samples) and all(
        truth(item, "selected") and truth(item, "autoEnabled")
        and not truth(item, "receiptFailure")
        for item in minute_samples
    )

    detail_lines = [line for line in lines if "WIMT_SOAK_DETAIL " in line]
    details = [fields(line) for line in detail_lines]
    periodic_details: dict[int, dict[str, str]] = {}
    final_detail: dict[str, str] = {}
    for item in details:
        cycle = item.get("cycle", "")
        if cycle == "final":
            final_detail = item
        elif cycle.isdigit():
            periodic_details[int(cycle)] = item
    checkpoint_set = set(REQUIRED_CHECKPOINTS)
    checkpoints_ok = (
        checkpoint_set.issubset(periodic_details)
        and all(_strict_detail(periodic_details[minute], require_station=True)
                for minute in REQUIRED_CHECKPOINTS)
        and _strict_detail(final_detail, require_station=True)
    ) if require_station else True

    initial_ok = (
        initial.get("httpStatus") == "200"
        and truth(initial, "runDateMatched")
        and truth(initial, "routeMatched")
        and truth(initial, "fresh")
        and number(initial, "rows") > 0
        and number(initial, "cells") > 0
        and truth(initial, "selected")
        and truth(initial, "disclosed")
        and truth(initial, "accepted")
        and (not require_station or (
            bool(initial.get("currentStationClaim", "").strip())
            and initial.get("currentStationClaim", "").lower() != "unknown"
        ))
    )

    try:
        instrumentation = (evidence / "instrumentation.txt").read_text(
            encoding="utf-8", errors="replace")
    except OSError:
        instrumentation = ""
    instrumentation_passed = "OK (1 test)" in instrumentation and "FAILURES!!!" not in instrumentation
    try:
        runner_exit = number(fields((evidence / "runner-exit.txt").read_text(encoding="utf-8")),
                             "instrumentationExitCode", -1)
    except OSError:
        runner_exit = -1

    captured_screenshots: list[str] = []
    screens_dir = evidence / "screens"
    if screens_dir.is_dir():
        for image in sorted(screens_dir.glob("wimt-soak-*.png")):
            hierarchy = image.with_suffix(".xml")
            if valid_png(image) and valid_app_hierarchy(hierarchy):
                captured_screenshots.append(str(image.relative_to(evidence)))
    screenshot_stems = {Path(item).stem for item in captured_screenshots}
    required_screenshots = {
        "wimt-soak-start", "wimt-soak-15m", "wimt-soak-30m",
        "wimt-soak-45m", "wimt-soak-46m",
    }
    screenshots_ok = required_screenshots.issubset(screenshot_stems)

    duration_ms = number(summary, "durationMs")
    cycles = number(summary, "cycles")
    required_receipts = number(summary, "requiredReceipts", REQUIRED_MINUTES)
    missed_receipts = number(summary, "missedReceipts")
    full_duration = truth(summary, "fullDuration") and duration_ms >= REQUIRED_MINUTES * 60_000
    current_station_ok = (
        require_station
        and truth(summary, "currentStationChecksPassed")
        and initial.get("currentStationClaim", "unknown").lower() != "unknown"
        and checkpoints_ok
    )
    auto_off = truth(summary, "autoOff") and truth(auto, "enabled")
    checks = {
        "initialFreshRunRouteMatchedWimtInResponse": initial_ok,
        "freshCurrentStationClaimAtStartAndCheckpoints": current_station_ok,
        "elapsedAtLeast46Minutes": full_duration,
        "atLeast46DistinctOneMinuteReceipts": cycles >= REQUIRED_MINUTES and required_receipts >= REQUIRED_MINUTES,
        "minuteZeroThrough46SamplesPresent": sample_minutes_ok,
        "selectedWimtAndAutoStayedOnWithoutReceiptFailure": receipt_samples_ok,
        "noMissedOneMinuteReceiptSlots": missed_receipts == 0,
        "noHttpOrUnqualifiedSamples": number(summary, "httpFailures") == 0 and
            number(summary, "unqualifiedSamples") == 0,
        "selectedSourceWasNotLost": number(summary, "selectedLosses") == 0 and
            truth(summary, "finalSelected"),
        "AutoWasTurnedOffAtEnd": auto_off,
        "genuineAppScreenshotsAndHierarchiesAtRequiredMilestones": screenshots_ok,
        "instrumentationPassed": instrumentation_passed and runner_exit == 0,
    }
    passed = all(checks.values())
    if passed:
        failure_stage = "PASS"
    elif not train_line:
        failure_stage = "TRAIN_NOT_SELECTED_OR_SETUP_FAILED"
    elif initial_line and not initial_ok:
        failure_stage = "INITIAL_WIMT_LIVE_STATUS_GATE_FAILED"
    elif started_line and not summary_line:
        failure_stage = "OBSERVATION_DID_NOT_REACH_FINAL_SUMMARY"
    elif summary_line and not full_duration:
        failure_stage = "OBSERVATION_ENDED_BEFORE_46_MINUTES"
    elif summary_line and not current_station_ok:
        failure_stage = "CURRENT_STATION_CLAIM_NOT_CONTINUOUSLY_QUALIFIED"
    elif summary_line and (not sample_minutes_ok or not receipt_samples_ok or missed_receipts):
        failure_stage = "MINUTE_REFRESH_SEQUENCE_INCOMPLETE"
    elif summary_line and not screenshots_ok:
        failure_stage = "REQUIRED_REAL_APP_SCREEN_EVIDENCE_MISSING"
    elif not instrumentation_passed or runner_exit != 0:
        failure_stage = "INSTRUMENTATION_OR_RUNNER_FAILED"
    else:
        failure_stage = "OTHER_ACCEPTANCE_CHECK_FAILED"

    checkpoints = []
    for minute in REQUIRED_CHECKPOINTS:
        item = periodic_details.get(minute, {})
        checkpoints.append({
            "minute": minute,
            "currentStationCodeClaim": item.get("currentStationClaim"),
            "httpStatus": number(item, "httpStatus", -1),
            "runDateMatched": truth(item, "runDateMatched"),
            "routeMatched": truth(item, "routeMatched"),
            "freshSourceStamp": truth(item, "fresh"),
            "sourceRows": number(item, "rows"),
            "visibleSourceCells": number(item, "cells"),
            "qualified": _strict_detail(item, require_station=True),
        })
    if final_detail:
        checkpoints.append({
            "minute": REQUIRED_MINUTES,
            "currentStationCodeClaim": final_detail.get("currentStationClaim"),
            "httpStatus": number(final_detail, "httpStatus", -1),
            "runDateMatched": truth(final_detail, "runDateMatched"),
            "routeMatched": truth(final_detail, "routeMatched"),
            "freshSourceStamp": truth(final_detail, "fresh"),
            "sourceRows": number(final_detail, "rows"),
            "visibleSourceCells": number(final_detail, "cells"),
            "qualified": _strict_detail(final_detail, require_station=True),
        })

    result = {
        "verdict": "PASS_46_MINUTE_WIMT_IN_APP_STATUS_OBSERVATION" if passed else
            "NO_VERIFIED_46_MINUTE_WIMT_IN_APP_OBSERVATION",
        "passed": passed,
        "failureStage": failure_stage,
        "requiredDurationMinutes": REQUIRED_MINUTES,
        "observedDurationMs": duration_ms if summary_line else None,
        "observedDurationMinutes": round(duration_ms / 60_000, 2) if summary_line else None,
        "trainNo": train.get("trainNo"),
        "runDate": train.get("runDate", "").replace("_", " ") or None,
        "route": train.get("route", "").replace("_", " ") or None,
        "trainSelectionRule": train.get("selection"),
        "source": "whereismytrain.in/cache/live_status (selected WIMT .in feed inside the installed APK; may be server-cached)",
        "sourceProvenanceLimit": PROVENANCE,
        "initial": {
            "accepted": initial_ok,
            "httpStatus": number(initial, "httpStatus", -1),
            "runDateMatched": truth(initial, "runDateMatched"),
            "routeMatched": truth(initial, "routeMatched"),
            "freshSourceStamp": truth(initial, "fresh"),
            "sourceRows": number(initial, "rows"),
            "visibleSourceCells": number(initial, "cells"),
            "currentStationCodeClaim": initial.get("currentStationClaim"),
        },
        "refresh": {
            "receipts": cycles,
            "requiredReceipts": required_receipts,
            "missedReceiptSlots": missed_receipts,
            "samples": [
                {"minute": number(item, "minute", -1),
                 "elapsedSeconds": number(item, "elapsedSeconds", -1),
                 "receiptClock": item.get("receiptClock"),
                 "selectedWimt": truth(item, "selected"),
                 "autoEnabled": truth(item, "autoEnabled"),
                 "receiptFailure": truth(item, "receiptFailure"),
                 "visibleSourceCells": number(item, "visibleCells"),
                 "sourceRowsVisible": truth(item, "sourceRowsVisible")}
                for item in samples
            ],
        },
        "currentStationCheckpoints": checkpoints,
        "screenshots": captured_screenshots,
        "checks": checks,
        "independentlyVerifiedMovement": False,
        "gpsVerified": False,
        "instrumentationPassed": instrumentation_passed,
        "runnerExitCode": runner_exit,
    }
    return result


def render_markdown(result: dict[str, Any]) -> str:
    lines = [
        "# One-train, 46-minute WIMT installed-app observation",
        "",
        f"- Verdict: **{result['verdict']}**",
        f"- Passed: **{result['passed']}** ({result['failureStage']})",
        f"- Train: **{result.get('trainNo') or 'not recorded'}**; run date: **{result.get('runDate') or 'not recorded'}**",
        f"- Route: **{result.get('route') or 'not recorded'}**",
        f"- Source: `{result['source']}`",
        f"- Elapsed: **{result.get('observedDurationMinutes') if result.get('observedDurationMinutes') is not None else 'not recorded'} / 46 minutes**",
        f"- One-minute refresh receipts / missed slots: **{result['refresh']['receipts']} / {result['refresh']['missedReceiptSlots']}**",
        f"- Start current-station claim: **{result['initial']['currentStationCodeClaim'] or 'not recorded'}**",
        "",
        "## Current-station source claims",
        "",
        "| Checkpoint minute | WIMT .in current-station code claim | HTTP | Run/route matched | Fresh stamp | Source rows | Qualified |",
        "|---:|---|---:|---|---|---:|---|",
    ]
    for item in result["currentStationCheckpoints"]:
        lines.append(
            f"| {item['minute']} | {item.get('currentStationCodeClaim') or '—'} | "
            f"{item['httpStatus'] if item['httpStatus'] >= 0 else '—'} | "
            f"{item['runDateMatched']}/{item['routeMatched']} | "
            f"{item['freshSourceStamp']} | {item['sourceRows']} | {item['qualified']} |"
        )
    lines.extend([
        "",
        "## Minute-by-minute app refresh receipts",
        "",
        "| Minute | Elapsed seconds | Receipt clock | WIMT selected | Auto on | Receipt failure | Visible WIMT cells |",
        "|---:|---:|---|---|---|---|---:|",
    ])
    for item in result["refresh"]["samples"]:
        lines.append(
            f"| {item['minute']} | {item['elapsedSeconds']} | {item.get('receiptClock') or '—'} | "
            f"{item['selectedWimt']} | {item['autoEnabled']} | {item['receiptFailure']} | "
            f"{item['visibleSourceCells']} |"
        )
    lines.extend(["", "## Acceptance checks", ""])
    lines.extend(f"- {name}: **{value}**" for name, value in result["checks"].items())
    lines.extend(["", "## Source limitations", "", result["sourceProvenanceLimit"], ""])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence_dir", type=Path)
    args = parser.parse_args()
    args.evidence_dir.mkdir(parents=True, exist_ok=True)
    result = build_report(args.evidence_dir)
    (args.evidence_dir / "result.json").write_text(
        json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (args.evidence_dir / "report.md").write_text(render_markdown(result), encoding="utf-8")
    print(json.dumps({"verdict": result["verdict"], "passed": result["passed"],
                      "trainNo": result["trainNo"], "receipts": result["refresh"]["receipts"],
                      "durationMinutes": result["observedDurationMinutes"]}, sort_keys=True))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
