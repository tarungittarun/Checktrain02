#!/usr/bin/env python3
"""Strict redacted report for one installed-app WIMT observation: 60 minutes / 30-second UI samples.

The source itself refreshes at the app's normal one-minute cadence; half-minute samples observe the
rendered state and do not issue extra network requests. The report never treats a feed claim as GPS
or independently verified train movement.
"""
from __future__ import annotations

import argparse
import json
import re
import struct
import zlib
from pathlib import Path

DURATION_MINUTES = 60
OBSERVATION_SECONDS = 30
EXPECTED_TARGETS = tuple(range(0, DURATION_MINUTES * 60 + 1, OBSERVATION_SECONDS))
CHECKPOINTS = tuple(range(5, DURATION_MINUTES, 5))
PROVENANCE = (
    "The installed app selected the direct WIMT .in live-status feed. Source requests used the app's "
    "normal one-minute Auto cadence; 30-second samples only observed the rendered UI. Source-update "
    "stamps and current-station fields are provider claims, not event times, independent railway "
    "confirmation, GPS, or proof of movement. The endpoint may be server-cached. NTES and the "
    "Where Is My Train .org.in website are not merged into this WIMT result."
)


def fields(line: str) -> dict[str, str]:
    return dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)=([^\s]+)", line))


def integer(data: dict[str, str], key: str, default: int = 0) -> int:
    try:
        return int(data.get(key, str(default)))
    except (TypeError, ValueError, OverflowError):
        return default


def yes(data: dict[str, str], key: str) -> bool:
    return data.get(key, "").lower() == "true"


def strict_detail(item: dict[str, str]) -> bool:
    station = item.get("currentStationClaim", "").strip().lower()
    return (
        item.get("httpStatus") == "200"
        and yes(item, "runDateMatched") and yes(item, "routeMatched")
        and yes(item, "fresh") and yes(item, "freshnessAccurate")
        and integer(item, "rows") > 0 and integer(item, "cells") > 0
        and yes(item, "selected") and yes(item, "disclosed")
        and station not in {"", "unknown"} and yes(item, "wimtReportsRunning")
    )


def valid_screenshot(path: Path) -> bool:
    try:
        data = path.read_bytes()
        if len(data) <= 1000 or data[:8] != b"\x89PNG\r\n\x1a\n":
            return False
        offset = 8
        width = height = 0
        saw_idat = saw_iend = False
        first = True
        while offset + 12 <= len(data):
            size = struct.unpack(">I", data[offset:offset + 4])[0]
            kind = data[offset + 4:offset + 8]
            end = offset + 8 + size
            if end + 4 > len(data):
                return False
            payload = data[offset + 8:end]
            if zlib.crc32(kind + payload) & 0xFFFFFFFF != struct.unpack(">I", data[end:end + 4])[0]:
                return False
            if first:
                if kind != b"IHDR" or size != 13:
                    return False
                width, height = struct.unpack(">II", payload[:8])
                first = False
            saw_idat |= kind == b"IDAT"
            if kind == b"IEND":
                if size != 0:
                    return False
                saw_iend = True
                offset = end + 4
                break
            offset = end + 4
        return not first and width > 0 and height > 0 and saw_idat and saw_iend and offset == len(data)
    except (OSError, struct.error, OverflowError):
        return False


def valid_hierarchy(path: Path) -> bool:
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
        return len(text) > 100 and 'package="in.opentrack.india"' in text
    except OSError:
        return False


def build_report(evidence: Path) -> dict:
    trace_path = evidence / "soak-trace.txt"
    trace = trace_path.read_text(encoding="utf-8", errors="replace") if trace_path.is_file() else ""
    lines = trace.splitlines()
    def one(prefix: str) -> dict[str, str]:
        line = next((line for line in lines if prefix in line), "")
        return fields(line)
    train = one("WIMT_SOAK_TRAIN ")
    initial = one("WIMT_SOAK_INITIAL ")
    started = one("WIMT_SOAK_STARTED ")
    summary = fields(next((line for line in reversed(lines) if "WIMT_SOAK_SUMMARY " in line), ""))
    auto = fields(next((line for line in lines if "WIMT_SOAK_AUTO_ENABLE " in line), ""))

    observations = [fields(line) for line in lines if "WIMT_SOAK_OBSERVATION " in line]
    by_target: dict[int, dict[str, str]] = {}
    duplicate_targets = 0
    for item in observations:
        target = integer(item, "targetElapsedSeconds", -1)
        if target in by_target:
            duplicate_targets += 1
        by_target[target] = item
    targets_ok = duplicate_targets == 0 and set(by_target) == set(EXPECTED_TARGETS)
    ui_samples_ok = targets_ok and all(
        yes(by_target[second], "selected")
        and integer(by_target[second], "visibleCells") > 0
        and yes(by_target[second], "sourceRowsVisible")
        and by_target[second].get("summaryClass") in {"FRESH_LABEL", "CHECKING"}
        and yes(by_target[second], "trackingScreen")
        and yes(by_target[second], "appForeground")
        for second in EXPECTED_TARGETS
    )
    jitter_ok = integer(summary, "missedObservationSlots", -1) == 0 and \
        integer(summary, "maxObservationJitterMs", 99_999) <= 10_000
    observation_count_ok = (
        integer(summary, "observationSamples", -1) == len(EXPECTED_TARGETS)
        and integer(summary, "expectedObservationSamples", -1) == len(EXPECTED_TARGETS)
    )

    initial_ok = strict_detail({
        **initial,
        "fresh": initial.get("fresh", "false"),
        "freshnessAccurate": initial.get("freshnessAccurate", "false"),
        "cells": initial.get("cells", "0"),
        "disclosed": initial.get("disclosed", "false"),
        "selected": initial.get("selected", "false"),
    }) and yes(initial, "accepted")

    detail_lines = [fields(line) for line in lines if "WIMT_SOAK_DETAIL " in line]
    details_by_minute = {
        integer(item, "elapsedMinutes", -1): item for item in detail_lines
        if item.get("cycle") != "final"
    }
    final_detail = next((item for item in detail_lines if item.get("cycle") == "final"), {})
    checkpoints_ok = set(CHECKPOINTS).issubset(details_by_minute) and all(
        strict_detail(details_by_minute[minute]) for minute in CHECKPOINTS
    ) and strict_detail(final_detail)

    try:
        instrumentation = (evidence / "instrumentation.txt").read_text(encoding="utf-8", errors="replace")
    except OSError:
        instrumentation = ""
    instrumentation_ok = "OK (1 test)" in instrumentation and "FAILURES!!!" not in instrumentation
    try:
        runner_line = (evidence / "runner-exit.txt").read_text(encoding="utf-8")
        runner_exit = integer(fields(runner_line), "instrumentationExitCode", -1)
    except OSError:
        runner_exit = -1

    screen_dir = evidence / "screens"
    screenshots = []
    if screen_dir.is_dir():
        for image in sorted(screen_dir.glob("wimt-soak-*.png")):
            if valid_screenshot(image) and valid_hierarchy(image.with_suffix(".xml")):
                screenshots.append(image.stem)
    required_screens = {"wimt-soak-start", "wimt-soak-15m", "wimt-soak-30m",
                        "wimt-soak-45m", "wimt-soak-60m"}
    screenshots_ok = required_screens.issubset(set(screenshots))

    duration_ms = integer(summary, "durationMs")
    cycles = integer(summary, "cycles")
    source_checks_ok = (
        cycles >= DURATION_MINUTES
        and integer(summary, "requiredReceipts", -1) >= DURATION_MINUTES
        and integer(summary, "missedReceipts", 999) == 0
        and integer(summary, "httpFailures", 999) == 0
        and integer(summary, "unqualifiedSamples", 999) == 0
        and integer(summary, "selectedLosses", 999) == 0
        and yes(summary, "currentStationChecksPassed")
        and yes(summary, "wimtRunningStatusChecksPassed")
        and yes(summary, "finalSelected")
        and yes(summary, "autoOff")
        and yes(auto, "enabled")
    )
    checks = {
        "oneRunningTrainSelectedAndRunRouteMatched": bool(re.fullmatch(r"\d{4,5}", train.get("trainNo", "")))
            and yes(initial, "runDateMatched") and yes(initial, "routeMatched"),
        "initialFreshWimtRunningStatusQualified": initial_ok,
        "full60MinutesElapsed": yes(summary, "fullDuration") and duration_ms >= 60 * 60_000,
        "all121UiObservationsPresentAt30SecondTargets": targets_ok and observation_count_ok,
        "everyUiObservationShowsSelectedFreshSourceData": ui_samples_ok,
        "observationJitterWithin10SecondsAndNoMissedSlots": jitter_ok,
        "60OneMinuteSourceReceiptsAndNoUnqualifiedResponses": source_checks_ok,
        "freshMatchedCurrentStationAndRunningGateAtFiveMinuteCheckpoints": checkpoints_ok,
        "genuineAppWindowCapturesAtStartAnd15MinuteMilestones": screenshots_ok,
        "instrumentationPassed": instrumentation_ok and runner_exit == 0,
    }
    passed = all(checks.values())
    failure_stage = "PASS" if passed else (
        "NO_FRESH_MATCHED_RUNNING_TRAIN_AT_START" if initial and not initial_ok else
        "OBSERVATION_INCOMPLETE_OR_SOURCE_UNRELIABLE" if summary else
        "SETUP_OR_INSTRUMENTATION_FAILED"
    )
    checkpoint_rows = [{
        "minute": minute,
        "httpStatus": integer(item, "httpStatus", -1),
        "runDateMatched": yes(item, "runDateMatched"),
        "routeMatched": yes(item, "routeMatched"),
        "fresh": yes(item, "fresh"),
        "currentStationClaim": item.get("currentStationClaim"),
        "sourceRows": integer(item, "rows"),
        "wimtReportsRunning": yes(item, "wimtReportsRunning"),
        "qualified": strict_detail(item),
    } for minute, item in sorted(details_by_minute.items())]
    if final_detail:
        checkpoint_rows.append({
            "minute": 60,
            "httpStatus": integer(final_detail, "httpStatus", -1),
            "runDateMatched": yes(final_detail, "runDateMatched"),
            "routeMatched": yes(final_detail, "routeMatched"),
            "fresh": yes(final_detail, "fresh"),
            "currentStationClaim": final_detail.get("currentStationClaim"),
            "sourceRows": integer(final_detail, "rows"),
            "wimtReportsRunning": yes(final_detail, "wimtReportsRunning"),
            "qualified": strict_detail(final_detail),
        })
    return {
        "verdict": "PASS_60_MINUTE_WIMT_APP_SOURCE_RELIABILITY" if passed else
            "NO_VERIFIED_60_MINUTE_WIMT_APP_SOURCE_RELIABILITY",
        "passed": passed,
        "failureStage": failure_stage,
        "trainNo": train.get("trainNo"),
        "runDate": train.get("runDate"),
        "route": train.get("route"),
        "source": "WIMT .in live-status feed through the installed APK",
        "durationMinutes": round(duration_ms / 60_000, 2) if summary else None,
        "uiObservationCount": len(observations),
        "expectedUiObservationCount": len(EXPECTED_TARGETS),
        "uiObservationIntervalSeconds": OBSERVATION_SECONDS,
        "sourceRefreshReceipts": cycles,
        "sourceRefreshCadenceMinutes": 1,
        "checkpoints": checkpoint_rows,
        "screenshots": screenshots,
        "checks": checks,
        "independentlyVerifiedMovement": False,
        "gpsVerified": False,
        "provenance": PROVENANCE,
    }


def render_markdown(result: dict) -> str:
    lines = [
        "# 60-minute WIMT installed-app source reliability observation", "",
        f"**Verdict:** `{result['verdict']}`", "",
        f"- Train: {result.get('trainNo') or 'not recorded'}; run date: {result.get('runDate') or 'not recorded'}",
        f"- Route: {result.get('route') or 'not recorded'}",
        f"- Duration: {result.get('durationMinutes') if result.get('durationMinutes') is not None else 'not recorded'} / 60 minutes",
        f"- UI samples: {result['uiObservationCount']} / {result['expectedUiObservationCount']} at 30-second targets",
        f"- WIMT .in source refresh receipts: {result['sourceRefreshReceipts']} at the app's one-minute cadence",
        "- These UI samples do not trigger extra network requests.", "",
        "## Five-minute WIMT running-status qualification checkpoints", "",
        "| Minute | HTTP | Run/route | Fresh | Current-station claim | Rows | Qualified |",
        "|---:|---:|---|---|---|---:|---|",
    ]
    for item in result["checkpoints"]:
        lines.append(f"| {item['minute']} | {item['httpStatus'] if item['httpStatus'] >= 0 else '—'} | "
                     f"{item['runDateMatched']}/{item['routeMatched']} | {item['fresh']} | "
                     f"{item.get('currentStationClaim') or '—'} | {item['sourceRows']} | {item['qualified']} |")
    lines += ["", "## Acceptance checks", ""]
    lines.extend(f"- {'PASS' if ok else 'FAIL'} — {name}" for name, ok in result["checks"].items())
    lines += ["", "## Evidence limits", "", result["provenance"], ""]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence_dir", type=Path)
    args = parser.parse_args()
    args.evidence_dir.mkdir(parents=True, exist_ok=True)
    result = build_report(args.evidence_dir)
    (args.evidence_dir / "result.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n",
                                                      encoding="utf-8")
    (args.evidence_dir / "report.md").write_text(render_markdown(result), encoding="utf-8")
    print(json.dumps({"verdict": result["verdict"], "passed": result["passed"],
                      "trainNo": result.get("trainNo"), "uiObservations": result["uiObservationCount"],
                      "sourceReceipts": result["sourceRefreshReceipts"]}, sort_keys=True))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
