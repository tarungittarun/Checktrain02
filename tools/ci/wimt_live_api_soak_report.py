#!/usr/bin/env python3
"""Redacted evidence report for the installed-app 240-minute WIMT .in soak.

Only bounded counts, HTTP status and run/route/freshness booleans are published. The
source response body, request URL, headers, cookies and stop identifiers are never copied.
"""
from __future__ import annotations

import argparse
import json
import re
import struct
from pathlib import Path

DURATION_MS = 240 * 60 * 1000
PROVENANCE = (
    "This test observes the selected WIMT .in source's own response and the installed app's "
    "rendered table. These are source-reported clocks, not independently verified railway "
    "actuals, train movement, GPS, provider permission, the Google WIMT phone app, or the "
    "owner's Android phone. The endpoint may itself be server-cached."
)


def fields(line: str) -> dict[str, str]:
    return dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)=([^\s]+)", line))


def number(data: dict[str, str], key: str, default: int = 0) -> int:
    try:
        return int(data.get(key, str(default)))
    except (TypeError, ValueError):
        return default


def truth(data: dict[str, str], key: str) -> bool:
    return data.get(key, "").lower() == "true"


def valid_png(path: Path) -> bool:
    try:
        with path.open("rb") as stream:
            header = stream.read(24)
        if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n":
            return False
        width, height = struct.unpack(">II", header[16:24])
        return width > 0 and height > 0 and path.stat().st_size > 1000
    except OSError:
        return False


def build_report(directory: Path) -> dict:
    trace_path = directory / "soak-trace.txt"
    if not trace_path.is_file():
        trace_path = directory / "wimt-soak-results.txt"
    trace = trace_path.read_text(encoding="utf-8", errors="replace") if trace_path.is_file() else ""
    lines = trace.splitlines()
    initial_line = next((line for line in lines if "WIMT_SOAK_INITIAL " in line), "")
    started_line = next((line for line in lines if "WIMT_SOAK_STARTED " in line), "")
    summary_line = next((line for line in reversed(lines) if "WIMT_SOAK_SUMMARY " in line), "")
    initial = fields(initial_line)
    started = fields(started_line)
    summary = fields(summary_line)

    instrumentation_path = directory / "instrumentation.txt"
    instrumentation = instrumentation_path.read_text(
        encoding="utf-8", errors="replace") if instrumentation_path.is_file() else ""
    instrumentation_passed = "OK (1 test)" in instrumentation and "FAILURES!!!" not in instrumentation
    runner_exit_path = directory / "runner-exit.txt"
    runner_exit = None
    if runner_exit_path.is_file():
        try:
            runner_exit = int(fields(runner_exit_path.read_text(encoding="utf-8")).get(
                "instrumentationExitCode", ""))
        except (TypeError, ValueError):
            runner_exit = None

    captured = sorted(
        str(path.relative_to(directory))
        for path in (directory / "screens").glob("*.png")
        if valid_png(path)
    ) if (directory / "screens").is_dir() else []

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
    )
    duration_ms = number(summary, "durationMs")
    cycles = number(summary, "cycles")
    missed = number(summary, "missedReceipts")
    http_failures = number(summary, "httpFailures")
    unqualified = number(summary, "unqualifiedSamples")
    selected_losses = number(summary, "selectedLosses")
    auto_off = truth(summary, "autoOff")
    full_duration = truth(summary, "fullDuration") and duration_ms >= DURATION_MS
    final_selected = truth(summary, "finalSelected")

    checks = {
        "initialStrictFreshInAppAcceptance": initial_ok,
        "full240MinuteDuration": full_duration,
        "atLeast230OneMinuteReceipts": cycles >= 230,
        "atMost10MissedReceiptSlots": missed <= 10,
        "noHttpFailures": http_failures == 0,
        "noUnqualifiedRunRouteSamples": unqualified == 0,
        "selectedWimtNeverLost": selected_losses == 0 and final_selected,
        "autoVerifiedOffAtEnd": auto_off,
        "instrumentationPassed": instrumentation_passed and runner_exit == 0,
    }
    passed = all(checks.values())

    result = {
        "verdict": "PASS_240_MINUTE_INSTALLED_APP_WIMT_SOAK" if passed else
            "FAIL_OR_INCOMPLETE_240_MINUTE_INSTALLED_APP_WIMT_SOAK",
        "passed": passed,
        "requiredDurationMinutes": 240,
        "durationMs": duration_ms if summary else None,
        "durationMinutes": round(duration_ms / 60_000, 2) if summary else None,
        "initial": {
            "accepted": initial_ok,
            "httpStatus": initial.get("httpStatus"),
            "runDateMatched": truth(initial, "runDateMatched"),
            "routeMatched": truth(initial, "routeMatched"),
            "freshSourceStamp": truth(initial, "fresh"),
            "sourceRows": number(initial, "rows"),
            "visibleWimtAttributedCells": number(initial, "cells"),
            "selectedSource": truth(initial, "selected"),
            "provenanceDisclosed": truth(initial, "disclosed"),
        },
        "autoRefresh": {
            "receiptCycles": cycles,
            "missedReceiptSlots": missed,
            "detailSamples": number(summary, "detailSamples"),
            "freshSamples": number(summary, "freshSamples"),
            "nonFreshSamples": number(summary, "nonFreshSamples"),
            "httpFailures": http_failures,
            "unqualifiedSamples": unqualified,
            "selectedSourceLosses": selected_losses,
            "sourceFailureReason": summary.get("sourceFailureReason", "not recorded"),
            "stoppedAfterSourceFailure": truth(summary, "autoStoppedAfterSourceFailure"),
            "finalSourceStillWimt": final_selected,
            "autoOffVerified": auto_off,
        },
        "checks": checks,
        "instrumentationPassed": instrumentation_passed,
        "instrumentationExitCode": runner_exit,
        "capturedAppScreenshots": captured,
        "provenance": PROVENANCE,
    }

    (directory / "result.json").write_text(
        json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    lines_out = [
        "# WIMT .in · 240-minute installed-app regression",
        "",
        f"**Verdict:** `{result['verdict']}`",
        "",
        "## Result",
        "",
        f"- Duration: {result['durationMinutes'] if summary else 'not recorded'} minutes "
        f"(required: 240)",
        f"- Initial strict in-app acceptance: {'PASS' if initial_ok else 'FAIL / not established'}",
        f"- One-minute refresh receipts: {cycles} (minimum: 230); missed slots: {missed}",
        f"- Detailed response samples: {number(summary, 'detailSamples')}",
        f"- Fresh source-stamp samples: {number(summary, 'freshSamples')}; "
        f"non-fresh or unknown: {number(summary, 'nonFreshSamples')}",
        f"- HTTP failures: {http_failures}; unqualified samples: {unqualified}",
        f"- Selected-source losses: {selected_losses}; "
        f"final WIMT selection: {final_selected}",
        f"- Auto verified off at end: {auto_off}",
        f"- Instrumentation: {'PASS' if instrumentation_passed and runner_exit == 0 else 'FAIL / incomplete'}",
        f"- Source stop reason: `{summary.get('sourceFailureReason', 'not recorded')}`",
        "",
        "## Acceptance checks",
        "",
    ]
    for name, ok in checks.items():
        lines_out.append(f"- {'PASS' if ok else 'FAIL'} — {name}")
    lines_out.extend(["", "## In-app screenshots", ""])
    if captured:
        lines_out.extend(f"- `{name}`" for name in captured)
    else:
        lines_out.append("No valid in-app screenshots were recovered; none are fabricated.")
    lines_out.extend(["", "## Provenance limits", "", PROVENANCE, ""])
    (directory / "report.md").write_text("\n".join(lines_out), encoding="utf-8")
    return result


def self_test() -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        directory.mkdir(exist_ok=True)
        (directory / "screens").mkdir()
        initial = (
            "WIMT_SOAK_INITIAL httpStatus=200 runDateMatched=true routeMatched=true "
            "fresh=true rows=11 cells=4 selected=true disclosed=true accepted=true"
        )
        summary = (
            "WIMT_SOAK_SUMMARY durationMs=14400000 cycles=240 missedReceipts=0 "
            "detailSamples=25 freshSamples=2 nonFreshSamples=23 unqualifiedSamples=0 "
            "httpFailures=0 selectedLosses=0 autoStoppedAfterSourceFailure=false "
            "sourceFailureReason=none fullDuration=true finalSelected=true autoOff=true"
        )
        (directory / "soak-trace.txt").write_text(
            initial + "\nWIMT_SOAK_STARTED durationMinutes=240\n" + summary + "\n",
            encoding="utf-8")
        (directory / "instrumentation.txt").write_text("OK (1 test)\n", encoding="utf-8")
        (directory / "runner-exit.txt").write_text("instrumentationExitCode=0\n", encoding="utf-8")
        result = build_report(directory)
        assert result["passed"]
        assert result["autoRefresh"]["nonFreshSamples"] == 23
        (directory / "soak-trace.txt").write_text(initial + "\n", encoding="utf-8")
        result = build_report(directory)
        assert not result["passed"]
        assert result["durationMs"] is None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence_dir", nargs="?", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        print("WIMT 240-minute soak report self-test: PASS")
        return 0
    if args.evidence_dir is None:
        parser.error("evidence_dir is required unless --self-test is used")
    result = build_report(args.evidence_dir)
    print(json.dumps({"verdict": result["verdict"], "passed": result["passed"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
