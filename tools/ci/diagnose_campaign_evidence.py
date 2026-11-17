#!/usr/bin/env python3
"""Summarize a prior campaign slot's machine-readable evidence without guessing."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


def first_file(root: Path | None, name: str) -> Path | None:
    if root is None or not root.is_dir():
        return None
    matches = sorted(root.rglob(name))
    return matches[0] if matches else None


def read_json(path: Path | None) -> dict[str, Any]:
    if path is None:
        return {}
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def read_observations(path: Path | None) -> tuple[list[dict[str, Any]], int]:
    if path is None:
        return [], 0
    observations: list[dict[str, Any]] = []
    malformed = 0
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        return [], 0
    for line in lines:
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            malformed += 1
            continue
        if isinstance(value, dict):
            observations.append(value)
        else:
            malformed += 1
    return observations, malformed


def high_signal_lines(path: Path | None, limit: int = 12) -> list[str]:
    if path is None:
        return []
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []
    pattern = re.compile(
        r"FAILURES!!!|INSTRUMENTATION_STATUS_CODE|AssertionError|Assertion failed|"
        r"Timed out|timeout|NoMatchingViewException|NotFoundException|"
        r"Campaign UI test failed|java\.lang\.[A-Za-z]+Exception|"
        r"java\.lang\.AssertionError|FAILURE:|Error in .*Test",
        re.IGNORECASE,
    )
    picked: list[str] = []
    for line in lines:
        text = line.strip()
        if text and pattern.search(text) and text not in picked:
            picked.append(text[:500])
    return picked[-limit:]


def summarize(roster_root: Path | None, slot_root: Path) -> dict[str, Any]:
    roster_path = first_file(roster_root, "roster.json")
    result_path = first_file(slot_root, "slot-result.json")
    assignment_path = first_file(slot_root, "assignment.json")
    observations_path = first_file(slot_root, "observations.jsonl")
    instrumentation_path = first_file(slot_root, "instrumentation.txt")
    runner_exit_path = first_file(slot_root, "runner-exit.txt")
    roster = read_json(roster_path)
    result = read_json(result_path)
    assignment = read_json(assignment_path)
    observations, malformed = read_observations(observations_path)
    try:
        instrumentation_lines = instrumentation_path.read_text(
            encoding="utf-8", errors="replace"
        ).splitlines() if instrumentation_path else []
    except OSError:
        instrumentation_lines = []
    try:
        runner_exit = runner_exit_path.read_text(encoding="utf-8", errors="replace").strip() \
            if runner_exit_path else "unavailable"
    except OSError:
        runner_exit = "unavailable"

    minutes = sorted({
        minute for item in observations
        if isinstance((minute := item.get("minute")), int)
    })
    auto_on = sum(item.get("autoEnabled") is True for item in observations)
    screen_present = sum(item.get("screenPresent") is True for item in observations)
    receipt_samples = sum(item.get("newReceiptSincePrevious") is True for item in observations)
    receipts = [item.get("receiptClock") for item in observations if item.get("receiptClock")]
    missing_minutes = sorted(set(range(46)) - set(minutes))
    source_values = [
        value for value in (
            result.get("selectedSourceAtStart"), result.get("selectedSourceAtEnd")
        ) if value
    ]
    if not source_values:
        source_values = sorted({
            str(item.get("sourceLabel")) for item in observations if item.get("sourceLabel")
        })

    roster_assignments = roster.get("assignments")
    if not isinstance(roster_assignments, list):
        roster_assignments = []
    diagnostic = {
        "roster": {
            "status": roster.get("status", "unavailable"),
            "eligibleCount": roster.get("eligibleCount", len(roster_assignments)),
            "requiredSlots": roster.get("requiredSlots", 20),
            "probesAttempted": roster.get("probesAttempted"),
            "stopReason": roster.get("stopReason"),
            "rejectionCounts": roster.get("rejectionCounts", {}),
        },
        "slot": {
            "status": result.get("status", "android-result" if result_path else "result-unavailable"),
            "passed": result.get("passed"),
            "runnerExit": runner_exit,
            "instrumentationOkMarkerPresent": any("OK (1 test)" in line for line in instrumentation_lines),
            "instrumentationFailureMarkerPresent": any("FAILURES!!!" in line for line in instrumentation_lines),
            "instrumentationTail": [line[:500] for line in instrumentation_lines[-30:]],
            "trainNo": result.get("trainNo", assignment.get("trainNo")),
            "runDate": result.get("runDate", assignment.get("runDate")),
            "failure": result.get("failure") or result.get("reason"),
            "durationMs": result.get("durationMs"),
            "observationCount": result.get("observationCount", len(observations)),
            "expectedObservationCount": result.get("expectedObservationCount", 46),
            "minuteRange": [min(minutes), max(minutes)] if minutes else None,
            "missingMinutes": missing_minutes,
            "malformedObservationLines": malformed,
            "screenPresentSamples": result.get("screenPresentSamples", screen_present),
            "autoOnAtEverySample": result.get("autoOnAtEverySample"),
            "autoOnSamples": auto_on,
            "minuteRefreshReceipts": result.get("minuteRefreshReceipts", receipt_samples),
            "missedOrUnchangedReceiptSamples": result.get(
                "missedOrUnchangedReceiptSamples",
                max(0, 45 - receipt_samples),
            ),
            "receiptClocksFirstLast": [receipts[0], receipts[-1]] if receipts else [],
            "autoEnabledAtStart": result.get("autoEnabledAtStart"),
            "autoOffAtEnd": result.get("autoOffAtEnd"),
            "selectedSource": source_values,
            "screenshots": result.get("verifiedAppScreenshots", []),
            "instrumentationSignals": high_signal_lines(instrumentation_path),
        },
        "filesFound": {
            "roster": roster_path is not None,
            "slotResult": result_path is not None,
            "assignment": assignment_path is not None,
            "observations": observations_path is not None,
            "instrumentation": instrumentation_path is not None,
            "runnerExit": runner_exit_path is not None,
        },
    }
    return diagnostic


def render_markdown(value: dict[str, Any]) -> str:
    roster = value["roster"]
    slot = value["slot"]
    lines = [
        "### Previous campaign evidence diagnosis",
        "",
        f"- Roster: **{roster['eligibleCount']}/{roster['requiredSlots']}** eligible; "
        f"status `{roster['status']}`; probes `{roster['probesAttempted']}`; "
        f"stop reason `{roster['stopReason']}`.",
        f"- Slot: **{slot['status']}**, passed `{slot['passed']}`, train `{slot['trainNo']}`, "
        f"run date `{slot['runDate']}`.",
        f"- Failure/reason: `{slot['failure'] or 'none recorded'}`.",
        f"- Runner status: `{slot['runnerExit']}`; instrumentation `OK (1 test)` marker "
        f"`{slot['instrumentationOkMarkerPresent']}`; `FAILURES!!!` marker "
        f"`{slot['instrumentationFailureMarkerPresent']}`.",
        f"- Duration: `{slot['durationMs']}` ms; observations `{slot['observationCount']}/"
        f"{slot['expectedObservationCount']}`; minute range `{slot['minuteRange']}`; "
        f"missing minutes `{slot['missingMinutes']}`.",
        f"- Visible UI samples `{slot['screenPresentSamples']}`; Auto on samples "
        f"`{slot['autoOnSamples']}`; Auto on every sample `{slot['autoOnAtEverySample']}`.",
        f"- New minute receipts `{slot['minuteRefreshReceipts']}/45`; missed/unchanged "
        f"`{slot['missedOrUnchangedReceiptSamples']}`; Auto off at end `{slot['autoOffAtEnd']}`.",
        f"- Selected source: `{slot['selectedSource']}`; screenshot evidence "
        f"`{slot['screenshots']}`.",
        "- WIMT `.in` preflight is schedule/source corroboration, not proof of train movement or GPS.",
        "",
        "Instrumentation failure signals:",
    ]
    lines.extend(f"- `{line}`" for line in slot["instrumentationSignals"] or ["No matching failure line found."])
    return "\n".join(lines) + "\n"


def annotation_escape(value: str) -> str:
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--roster-dir", type=Path, required=True)
    parser.add_argument("--slot-dir", type=Path, required=True)
    parser.add_argument("--markdown", type=Path, required=True)
    parser.add_argument("--json", type=Path, required=True)
    args = parser.parse_args()
    value = summarize(args.roster_dir, args.slot_dir)
    if not value["filesFound"]["slotResult"]:
        parser.error("slot-result.json was not found in the downloaded prior-run artifact")
    markdown = render_markdown(value)
    args.markdown.parent.mkdir(parents=True, exist_ok=True)
    args.markdown.write_text(markdown, encoding="utf-8")
    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.json.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(markdown, end="")
    compact = (
        f"roster={value['roster']['eligibleCount']}/{value['roster']['requiredSlots']} "
        f"probes={value['roster']['probesAttempted']} stop={value['roster']['stopReason']}; "
        f"slotStatus={value['slot']['status']} passed={value['slot']['passed']} "
        f"runner={value['slot']['runnerExit']} okMarker={value['slot']['instrumentationOkMarkerPresent']} "
        f"failMarker={value['slot']['instrumentationFailureMarkerPresent']} "
        f"train={value['slot']['trainNo']} failure={value['slot']['failure']}; "
        f"observations={value['slot']['observationCount']}/{value['slot']['expectedObservationCount']} "
        f"receipts={value['slot']['minuteRefreshReceipts']}/45 "
        f"autoOn={value['slot']['autoOnSamples']}/46 "
        f"screens={value['slot']['screenPresentSamples']}/46"
    )
    print(f"::notice title=Previous campaign evidence::{annotation_escape(compact)}")
    log_tail = " | ".join(value["slot"]["instrumentationTail"][-20:]) or "no instrumentation output"
    print(f"::notice title=Prior instrumentation tail::{annotation_escape(log_tail[:6000])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
