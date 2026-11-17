#!/usr/bin/env python3
"""Redacted evidence report for a 30-minute installed-app WIMT observation.

The instrumented APK is sampled through its visible UI every 20 seconds. If the initial
WIMT .in response is unqualified, the app remains open and is observed passively for the
full interval with Auto OFF; no repeated request is made just to keep a failing source busy.
The app's existing Auto cadence, when enabled, remains one minute.
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import struct
from collections import Counter
from pathlib import Path

DURATION_MS = 30 * 60 * 1000
INTERVAL_SECONDS = 20
EXPECTED_SAMPLES = DURATION_MS // (INTERVAL_SECONDS * 1000) + 1
MAX_JITTER_MS = 10_000
PROVENANCE = (
    "This evidence comes from UIAutomator driving the APK installed in a CI Android emulator, "
    "not from a direct endpoint observer, the WIMT .org.in website, NTES, eRail, WIMT web cache, "
    "the Google WIMT Android app, or the owner's phone. WIMT .in clocks and station claims are "
    "source-reported and may be server-cached; they do not independently prove train movement or GPS. "
    "The 20-second cadence is UI observation; the app's existing opt-in Auto network cadence remains one minute."
)

ALLOWED_RESPONSE_CLASSES = {
    "HTTP_STATUS_UNKNOWN", "HTTP_200_NO_QUALIFIED_ROWS", "HTTP_200_SOURCE_ROWS_PRESENT",
    *(f"HTTP_{status}" for status in range(100, 600)),
}
ALLOWED_PARSER_OUTCOMES = {
    "RUN_DATE_MISMATCH", "ROUTE_MISMATCH", "TRAIN_MISMATCH", "INVALID_JSON",
    "NO_SCHEDULE_ARRAY", "RESPONSE_TOO_LARGE", "RUN_DATE_NOT_ECHOED",
    "ROUTE_NOT_FULLY_ECHOED", "PARSED_ZERO_SOURCE_ACTUAL_STOPS",
    "ZERO_QUALIFIED_SOURCE_ROWS", "SOURCE_ROWS_PRESENT",
}
ALLOWED_SUMMARY_CLASSES = {
    "EMPTY", "CHECKING", "STALE", "FAILURE", "UNQUALIFIED", "FRESH_LABEL", "OTHER",
}
ALLOWED_RECEIPT_CLASSES = {"NONE", "FAILURE", "CHECKED", "OTHER"}
ALLOWED_FAILURE_REASONS = {
    "none", "HTTP_403", "HTTP_429", "HTTP_5XX", "NO_HTTP_RESPONSE",
    "RUN_DATE_MISMATCH_OR_NOT_ECHOED", "ROUTE_MISMATCH_OR_NOT_ECHOED",
    "STALE_SOURCE_STAMP", "FRESHNESS_NOT_ESTABLISHED", "SOURCE_STAMP_NOT_FRESH",
    "NO_QUALIFIED_SOURCE_ACTUAL_ROWS", "NO_VISIBLE_WIMT_SOURCE_CELLS",
    "WIMT_SOURCE_NOT_SELECTED_IN_APP", "CURRENT_STATION_CLAIM_MISSING",
    "RUNNING_STATUS_NOT_QUALIFIED", "INITIAL_GATE_UNQUALIFIED",
    "AUTO_ENABLE_FAILED", "SELECTED_SOURCE_REFRESH_REPORTED_FAILURE",
    "AUTO_DISABLED_DURING_OBSERVATION", "SELECTED_WIMT_SOURCE_LOST",
    "AUTO_DID_NOT_STOP_AFTER_MINUTE_FAILURE", "AUTO_DID_NOT_STOP_AFTER_SOURCE_LOSS",
    "AUTO_DID_NOT_STOP_AFTER_OBSERVATION_FAILURE", "AUTO_REFRESH_RECEIPT_MISSING",
    "SELECTED_WIMT_DETAILS_UNAVAILABLE", "AUTO_DID_NOT_STOP_AFTER_DETAILS_LOSS",
    "UNQUALIFIED_SOURCE_RESPONSE", "AUTO_DID_NOT_STOP_AFTER_SOURCE_FAILURE",
    "APP_TRACKING_SCREEN_LOST",
}


def fields(line: str) -> dict[str, str]:
    return dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)=([^\s]+)", line))


def number(data: dict[str, str], key: str, default: int = 0) -> int:
    try:
        return int(data.get(key, str(default)))
    except (TypeError, ValueError):
        return default


def truth(data: dict[str, str], key: str) -> bool:
    return data.get(key, "").lower() == "true"


def optional_number(data: dict[str, str], key: str) -> int | None:
    try:
        return int(data[key])
    except (KeyError, TypeError, ValueError):
        return None


def optional_float(data: dict[str, str], key: str) -> float | None:
    try:
        value = float(data[key])
        return value if 0 <= value <= 1000 else None
    except (KeyError, TypeError, ValueError):
        return None


def safe_class(value: str, allowed: set[str]) -> str:
    return value if value in allowed else "unrecognized"


def safe_reason(value: str) -> str:
    return value if value in ALLOWED_FAILURE_REASONS or re.fullmatch(
        r"HTTP_[1-5][0-9]{2}", value) else "unrecognized"


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


def safe_count(values: Counter[str]) -> dict[str, int]:
    return {key: values[key] for key in sorted(values)}


def build_report(directory: Path) -> dict:
    trace_path = directory / "soak-trace.txt"
    if not trace_path.is_file():
        trace_path = directory / "wimt-soak-results.txt"
    trace = trace_path.read_text(encoding="utf-8", errors="replace") if trace_path.is_file() else ""
    lines = trace.splitlines()
    train_line = next((line for line in lines if "WIMT_SOAK_TRAIN " in line), "")
    initial_line = next((line for line in lines if "WIMT_SOAK_INITIAL " in line), "")
    auto_enable_line = next((line for line in lines if "WIMT_SOAK_AUTO_ENABLE " in line), "")
    started_line = next((line for line in lines if "WIMT_SOAK_STARTED " in line), "")
    summary_line = next((line for line in reversed(lines) if "WIMT_SOAK_SUMMARY " in line), "")
    train = fields(train_line)
    initial = fields(initial_line)
    auto_enable = fields(auto_enable_line)
    started = fields(started_line)
    summary = fields(summary_line)

    observation_fields = [
        fields(line) for line in lines if "WIMT_SOAK_OBSERVATION " in line
    ]
    observation_fields.sort(key=lambda item: number(item, "sample", -1))
    observed_sequences = [number(item, "sample", -1) for item in observation_fields]
    observed_targets = [number(item, "targetElapsedSeconds", -1) for item in observation_fields]
    expected_targets = list(range(0, DURATION_MS // 1000 + 1, INTERVAL_SECONDS))
    interval_sequence_complete = (
        observed_sequences == list(range(EXPECTED_SAMPLES))
        and observed_targets == expected_targets
    )
    jitter_values = [number(item, "jitterMs", -1) for item in observation_fields]
    jitter_values = [value for value in jitter_values if value >= 0]
    reported_jitter = number(summary, "maxObservationJitterMs", -1)
    max_jitter = max(jitter_values, default=reported_jitter if reported_jitter >= 0 else None)
    pss_values = [
        value for item in observation_fields
        if (value := optional_number(item, "processPssKb")) is not None
    ]
    cpu_values = [
        value for item in observation_fields
        if (value := optional_float(item, "processCpuPercent")) is not None
    ]
    selected_count = sum(truth(item, "selected") for item in observation_fields)
    visible_cell_counts = [number(item, "visibleCells") for item in observation_fields]
    source_rows_visible_count = sum(truth(item, "sourceRowsVisible") for item in observation_fields)
    tracking_screen_count = sum(truth(item, "trackingScreen") for item in observation_fields)
    foreground_count = sum(truth(item, "appForeground") for item in observation_fields)
    summary_classes = Counter(
        safe_class(item.get("summaryClass", "unrecorded"), ALLOWED_SUMMARY_CLASSES)
        for item in observation_fields
    )
    receipt_classes = Counter(
        safe_class(item.get("receiptClass", "unrecorded"), ALLOWED_RECEIPT_CLASSES)
        for item in observation_fields
    )
    cycle_fields = [fields(line) for line in lines if "WIMT_SOAK_CYCLE " in line]
    cycle_fields.sort(key=lambda item: number(item, "cycle", -1))
    receipt_elapsed_seconds = [
        value for item in cycle_fields
        if (value := optional_number(item, "elapsedSeconds")) is not None and value >= 0
    ]
    receipt_intervals_seconds = [
        right - left for left, right in zip(receipt_elapsed_seconds, receipt_elapsed_seconds[1:])
    ]

    passive_final_line = next((line for line in reversed(lines)
                               if "WIMT_SOAK_PASSIVE_FINAL " in line), "")
    passive_final = fields(passive_final_line)
    detail_fields = [fields(line) for line in lines if "WIMT_SOAK_DETAIL " in line]
    detail_response_classes = Counter(
        safe_class(item.get("responseClass", "not recorded"), ALLOWED_RESPONSE_CLASSES)
        for item in detail_fields
    )
    detail_parser_outcomes = Counter(
        safe_class(item.get("parserOutcome", "not recorded"), ALLOWED_PARSER_OUTCOMES)
        for item in detail_fields
    )
    detail_http_statuses = Counter(
        item.get("httpStatus", "unknown")
        if re.fullmatch(r"[1-5][0-9]{2}|none", item.get("httpStatus", "")) else "unknown"
        for item in detail_fields
    )
    response_bytes = [
        value for item in detail_fields
        if (value := optional_number(item, "responseBytes")) is not None
    ]
    detail_rows = [number(item, "rows") for item in detail_fields]
    fresh_detail_count = sum(truth(item, "fresh") for item in detail_fields)
    non_fresh_detail_count = len(detail_fields) - fresh_detail_count

    instrumentation_path = directory / "instrumentation.txt"
    instrumentation = instrumentation_path.read_text(
        encoding="utf-8", errors="replace") if instrumentation_path.is_file() else ""
    instrumentation_passed = (
        "OK (1 test)" in instrumentation and "FAILURES!!!" not in instrumentation
        and "INSTRUMENTATION_FAILED" not in instrumentation
    )
    runner_exit_path = directory / "runner-exit.txt"
    try:
        runner_exit = int(fields(runner_exit_path.read_text(encoding="utf-8")).get(
            "instrumentationExitCode", "")) if runner_exit_path.is_file() else None
    except (TypeError, ValueError):
        runner_exit = None
    boot_path = directory / "boot.txt"
    boot_log = boot_path.read_text(encoding="utf-8", errors="replace").lower() if boot_path.is_file() else ""

    screenshot_dir = directory / "screens"
    captured = sorted(
        path.name for path in screenshot_dir.glob("*.png")
        if re.fullmatch(r"[A-Za-z0-9._-]+\.png", path.name) and valid_png(path)
    ) if screenshot_dir.is_dir() else []

    response_class = safe_class(initial.get("responseClass", "not recorded"),
                                ALLOWED_RESPONSE_CLASSES)
    parser_outcome = safe_class(initial.get("parserOutcome", "not recorded"),
                                ALLOWED_PARSER_OUTCOMES)
    initial_response_bytes = optional_number(initial, "responseBytes")
    train_no = train.get("trainNo", "")
    if not re.fullmatch(r"\d{4,5}", train_no):
        train_no = "unknown"
    initial_ok = (
        initial.get("httpStatus") == "200"
        and truth(initial, "runDateMatched")
        and truth(initial, "routeMatched")
        and truth(initial, "fresh")
        and number(initial, "rows") > 0
        and number(initial, "cells") > 0
        and truth(initial, "selected")
        and truth(initial, "disclosed")
        and truth(initial, "wimtReportsRunning")
        and initial.get("currentStationClaim", "unknown") not in {"unknown", "", "none"}
        and truth(initial, "accepted")
    )
    duration_ms = number(summary, "durationMs")
    cycles = number(summary, "cycles")
    missed_receipts = number(summary, "missedReceipts")
    http_failures = number(summary, "httpFailures")
    unqualified = number(summary, "unqualifiedSamples")
    selected_losses = number(summary, "selectedLosses")
    auto_off = truth(summary, "autoOff")
    full_duration = truth(summary, "fullDuration") and duration_ms >= DURATION_MS
    final_selected = truth(summary, "finalSelected")
    observation_count = len(observation_fields)
    missed_observation_slots = number(summary, "missedObservationSlots", -1)
    if missed_observation_slots < 0:
        missed_observation_slots = sum("WIMT_SOAK_MISSED_OBSERVATION " in line for line in lines)
    observation_complete = (
        full_duration and interval_sequence_complete
        and observation_count == EXPECTED_SAMPLES
        and missed_observation_slots == 0
    )
    current_station_ok = truth(summary, "currentStationChecksPassed")
    running_status_ok = truth(summary, "wimtRunningStatusChecksPassed")
    source_reason = safe_reason(summary.get("sourceFailureReason", "not recorded"))
    started_auto = truth(summary, "autoStarted") or truth(started, "autoStarted")
    auto_verified_off = auto_off

    checks = {
        "initialStrictWimtInAppAcceptance": initial_ok,
        "full30MinuteInstalledAppObservation": full_duration,
        "all91SamplesAt20SecondTargets": observation_count == EXPECTED_SAMPLES and interval_sequence_complete,
        "noMissed20SecondObservationSlots": missed_observation_slots == 0,
        "maximumObservationJitterAtMost10Seconds": max_jitter is not None and max_jitter <= MAX_JITTER_MS,
        "30OneMinuteAutoReceipts": cycles >= 30,
        "noMissedAutoReceiptSlots": missed_receipts == 0,
        "noHttpFailures": http_failures == 0,
        "allDetailedWimtResponsesFresh": bool(detail_fields) and non_fresh_detail_count == 0,
        "noUnqualifiedSourceSamples": unqualified == 0,
        "selectedWimtSourceRetained": selected_losses == 0 and final_selected,
        "currentStationAndRunningStatusQualified": current_station_ok and running_status_ok,
        "autoOffVerifiedAtEnd": auto_verified_off,
        "instrumentationPassed": instrumentation_passed and runner_exit == 0,
    }
    passed = all(checks.values())
    failure_stage = "PASS" if passed else "SETUP_OR_DEVICE_TEST_NOT_COMPLETED"
    if not passed:
        if "emulator did not finish booting" in boot_log or "needs hardware acceleration" in boot_log:
            failure_stage = "EMULATOR_BOOT_FAILED"
        elif runner_exit == 3:
            failure_stage = "EMULATOR_SETUP_FAILED"
        elif initial_line and not initial_ok:
            failure_stage = "INITIAL_WIMT_IN_APP_GATE_FAILED"
        elif started_line and not observation_complete:
            failure_stage = "OBSERVATION_INTERVALS_INCOMPLETE"
        elif started_line and not started_auto:
            failure_stage = "AUTO_ENABLE_FAILED"
        elif started_line and (http_failures or unqualified or selected_losses):
            failure_stage = "WIMT_SOURCE_QUALIFICATION_FAILED"
        elif started_line and cycles < 30:
            failure_stage = "AUTO_REFRESH_RECEIPT_SHORTFALL"
        elif summary_line:
            failure_stage = "INSTALLED_APP_ACCEPTANCE_ASSERTION_FAILED"
        elif runner_exit is not None:
            failure_stage = "INSTRUMENTED_DEVICE_TEST_FAILED_BEFORE_OBSERVATION"

    if passed:
        failure_summary = "All installed-app, WIMT source, timing, and receipt checks passed."
    elif failure_stage == "INITIAL_WIMT_IN_APP_GATE_FAILED":
        failure_reason = safe_reason(initial.get("failureReason", summary.get("sourceFailureReason", "unrecognized")))
        failure_summary = (
            "The installed app opened the selected WIMT .in source, but its initial in-app gate "
            f"was not qualified: reason={failure_reason}, HTTP={initial.get('httpStatus', 'not recorded')}, "
            f"run-date-match={truth(initial, 'runDateMatched')}, route-match={truth(initial, 'routeMatched')}, "
            f"fresh={truth(initial, 'fresh')}, source-rows={number(initial, 'rows')}, "
            f"visible-cells={number(initial, 'cells')}, selected={truth(initial, 'selected')}, "
            f"current-station-claim-present={initial.get('currentStationClaim', 'unknown') not in {'unknown', '', 'none'}}, "
            f"running-status-qualified={truth(initial, 'wimtReportsRunning')}, "
            f"response-class={response_class}, parser-outcome={parser_outcome}, "
            f"response-bytes={initial_response_bytes if initial_response_bytes is not None else 'unknown'}. "
            + (f"The app was then observed passively for {round(duration_ms / 60_000, 2)} minutes; Auto remained OFF."
               if observation_complete else "The requested passive observation did not complete.")
        )
    elif failure_stage == "EMULATOR_BOOT_FAILED":
        failure_summary = "The emulator did not boot; no installed-app WIMT observation was established."
    elif failure_stage == "EMULATOR_SETUP_FAILED":
        failure_summary = "Emulator setup failed before the installed-app observation could run."
    elif failure_stage == "WIMT_SOURCE_QUALIFICATION_FAILED":
        reason = source_reason if source_reason != "none" else "UNQUALIFIED_DETAIL_SAMPLE"
        failure_summary = (
            f"The installed-app WIMT source did not remain qualified: reason={reason}; "
            f"HTTP failures={http_failures}, unqualified samples={unqualified}, "
            f"selected-source losses={selected_losses}. This run is not a pass."
        )
    else:
        failure_summary = "; ".join(name for name, ok in checks.items() if not ok)

    reported_train = train_no
    run_date = train.get("runDate", "")
    if not re.fullmatch(r"\d{1,2}_[A-Za-z]{3}_\d{4}", run_date):
        run_date = "unknown"
    route_label = train.get("route", "")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", route_label):
        route_label = "not recorded"
    result = {
        "verdict": "PASS_30_MIN_INSTALLED_APP_WIMT_20_SECOND_OBSERVATION" if passed else
            "FAIL_OR_INCOMPLETE_30_MIN_INSTALLED_APP_WIMT_20_SECOND_OBSERVATION",
        "passed": passed,
        "failureStage": failure_stage,
        "failureSummary": failure_summary,
        "trainNo": reported_train,
        "runDate": run_date,
        "routeLabel": route_label,
        "requiredDurationMinutes": 30,
        "durationMs": duration_ms if summary_line else None,
        "durationMinutes": round(duration_ms / 60_000, 2) if summary_line else None,
        "initialGateAccepted": initial_ok,
        "initial": {
            "httpStatus": initial.get("httpStatus"),
            "runDateMatched": truth(initial, "runDateMatched"),
            "routeMatched": truth(initial, "routeMatched"),
            "freshSourceStamp": truth(initial, "fresh"),
            "freshnessAccurate": truth(initial, "freshnessAccurate"),
            "sourceRows": number(initial, "rows"),
            "visibleWimtAttributedCells": number(initial, "cells"),
            "responseClass": response_class,
            "parserOutcome": parser_outcome,
            "responseBytes": initial_response_bytes,
            "selectedSource": truth(initial, "selected"),
            "currentStationClaimPresent": initial.get("currentStationClaim", "unknown") not in {"unknown", "", "none"},
            "runningStatusQualified": truth(initial, "wimtReportsRunning"),
            "provenanceDisclosed": truth(initial, "disclosed"),
            "failureReason": safe_reason(initial.get("failureReason", "none")),
        },
        "observation": {
            "requiredIntervalSeconds": INTERVAL_SECONDS,
            "expectedSamplesIncludingStartAndEnd": EXPECTED_SAMPLES,
            "observedSamples": observation_count,
            "missedSlots": missed_observation_slots,
            "sequenceAndTargetsComplete": interval_sequence_complete,
            "maximumJitterMs": max_jitter,
            "maximumAllowedJitterMs": MAX_JITTER_MS,
            "selectedWimtSamples": selected_count,
            "visibleCellSamples": sum(count > 0 for count in visible_cell_counts),
            "minimumVisibleCells": min(visible_cell_counts) if visible_cell_counts else None,
            "maximumVisibleCells": max(visible_cell_counts) if visible_cell_counts else None,
            "sourceRowsVisibleSamples": source_rows_visible_count,
            "trackingScreenVisibleSamples": tracking_screen_count,
            "appForegroundSamples": foreground_count,
            "summaryClasses": safe_count(summary_classes),
            "receiptClasses": safe_count(receipt_classes),
        },
        "autoRefresh": {
            "appAutoCadenceMinutes": 1,
            "started": started_auto,
            "receiptCycles": cycles,
            "requiredReceipts": number(summary, "requiredReceipts", 30),
            "receiptElapsedSeconds": {
                "sampleCount": len(receipt_elapsed_seconds),
                "first": receipt_elapsed_seconds[0] if receipt_elapsed_seconds else None,
                "last": receipt_elapsed_seconds[-1] if receipt_elapsed_seconds else None,
            },
            "receiptIntervalsSeconds": {
                "sampleCount": len(receipt_intervals_seconds),
                "minimum": min(receipt_intervals_seconds) if receipt_intervals_seconds else None,
                "median": round(statistics.median(receipt_intervals_seconds), 2)
                    if receipt_intervals_seconds else None,
                "maximum": max(receipt_intervals_seconds) if receipt_intervals_seconds else None,
            },
            "missedReceiptSlots": missed_receipts,
            "detailSamples": len(detail_fields),
            "freshDetailSamples": fresh_detail_count,
            "nonFreshOrUnknownDetailSamples": non_fresh_detail_count,
            "httpFailures": http_failures,
            "unqualifiedSamples": unqualified,
            "selectedSourceLosses": selected_losses,
            "sourceFailureReason": source_reason,
            "stoppedAfterSourceFailure": truth(summary, "autoStoppedAfterSourceFailure"),
            "finalSourceStillWimt": final_selected,
            "autoOffVerified": auto_verified_off,
            "passiveFailureReason": safe_reason(passive_final.get("sourceFailureReason", "none")),
            "passiveFinalSourceDetailsCaptured": truth(passive_final, "sourceDetailsCaptured"),
            "appRemainedOpenAfterSourceFailure": truth(passive_final, "appRemainedOpen"),
        },
        "sourceDiagnostics": {
            "httpStatusCounts": safe_count(detail_http_statuses),
            "responseClasses": safe_count(detail_response_classes),
            "parserOutcomes": safe_count(detail_parser_outcomes),
            "responseBytesMinimum": min(response_bytes) if response_bytes else None,
            "responseBytesMaximum": max(response_bytes) if response_bytes else None,
            "sourceRowsMinimum": min(detail_rows) if detail_fields else None,
            "sourceRowsMaximum": max(detail_rows) if detail_fields else None,
            "currentStationChecksPassed": current_station_ok,
            "runningStatusChecksPassed": running_status_ok,
        },
        "appProcessMemory": {
            "metric": "Android dumpsys meminfo total PSS, KB",
            "sampleCount": len(pss_values),
            "minimumKb": min(pss_values) if pss_values else None,
            "medianKb": round(statistics.median(pss_values)) if pss_values else None,
            "maximumKb": max(pss_values) if pss_values else None,
            "firstKb": pss_values[0] if pss_values else None,
            "lastKb": pss_values[-1] if pss_values else None,
        },
        "appProcessCpu": {
            "metric": "Android dumpsys cpuinfo process percentage",
            "sampleCount": len(cpu_values),
            "minimumPercent": min(cpu_values) if cpu_values else None,
            "medianPercent": round(statistics.median(cpu_values), 2) if cpu_values else None,
            "maximumPercent": max(cpu_values) if cpu_values else None,
            "firstPercent": cpu_values[0] if cpu_values else None,
            "lastPercent": cpu_values[-1] if cpu_values else None,
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
        "# WIMT .in · 30-minute installed-app observation (20-second samples)",
        "",
        f"**Verdict:** `{result['verdict']}`",
        f"**Failure stage:** `{failure_stage}`",
        f"**Train:** `{reported_train}` · **Run date:** `{run_date}` · **Route label:** `{route_label}`",
        f"**Diagnostic:** {failure_summary}",
        "",
        "## Observation",
        "",
        f"- App observation: {result['durationMinutes'] if summary_line else 'not recorded'} minutes "
        "(required: 30)",
        f"- UI samples: {observation_count}/{EXPECTED_SAMPLES}; target interval: 20 seconds; "
        f"missed slots: {missed_observation_slots}; maximum jitter: "
        f"{max_jitter if max_jitter is not None else 'unknown'} ms",
        f"- Tracking screen visible: {tracking_screen_count}/{observation_count}; "
        f"WIMT selected: {selected_count}/{observation_count}; "
        f"samples with visible WIMT cells: {sum(count > 0 for count in visible_cell_counts)}/{observation_count}",
        f"- Initial strict in-app WIMT acceptance: {'PASS' if initial_ok else 'FAIL / not established'}",
        f"- One-minute Auto: started `{started_auto}`; receipts {cycles}/30; "
        f"missed receipt slots: {missed_receipts}; observed receipt intervals "
        f"min/median/max seconds: "
        f"{min(receipt_intervals_seconds) if receipt_intervals_seconds else 'unknown'}/"
        f"{round(statistics.median(receipt_intervals_seconds), 2) if receipt_intervals_seconds else 'unknown'}/"
        f"{max(receipt_intervals_seconds) if receipt_intervals_seconds else 'unknown'}; "
        f"source failure: `{source_reason}`",
        f"- Detailed in-app source samples: {len(detail_fields)}; fresh: {fresh_detail_count}; "
        f"non-fresh or unknown: {non_fresh_detail_count}; HTTP failures: {http_failures}; "
        f"unqualified: {unqualified}",
        f"- Android app PSS: {len(pss_values)} samples; "
        f"min/median/max KB: {min(pss_values) if pss_values else 'unknown'}/"
        f"{round(statistics.median(pss_values)) if pss_values else 'unknown'}/"
        f"{max(pss_values) if pss_values else 'unknown'}",
        f"- Android app CPU: {len(cpu_values)} samples; "
        f"min/median/max percent: {min(cpu_values) if cpu_values else 'unknown'}/"
        f"{round(statistics.median(cpu_values), 2) if cpu_values else 'unknown'}/"
        f"{max(cpu_values) if cpu_values else 'unknown'}",
        f"- Auto verified OFF at end: {auto_verified_off}; instrumentation: "
        f"{'PASS' if instrumentation_passed and runner_exit == 0 else 'FAIL / incomplete'}",
        "",
        "## Acceptance checks",
        "",
    ]
    lines_out.extend(f"- {'PASS' if ok else 'FAIL'} — {name}" for name, ok in checks.items())
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

    assert safe_class("HTTP_503", ALLOWED_RESPONSE_CLASSES) == "HTTP_503"
    assert safe_reason("HTTP_503") == "HTTP_503"
    assert safe_reason("HTTP_999") == "unrecognized"

    with tempfile.TemporaryDirectory() as tmp:
        directory = Path(tmp)
        (directory / "screens").mkdir()
        initial = (
            "WIMT_SOAK_TRAIN trainNo=20651 runDate=12_Oct_2026 route=SBC_TLGP "
            "selection=workflow-assigned-train\n"
            "WIMT_SOAK_INITIAL httpStatus=200 responseBytes=4321 "
            "runDateMatched=true routeMatched=true fresh=true freshnessAccurate=true "
            "rows=11 cells=4 responseClass=HTTP_200_SOURCE_ROWS_PRESENT "
            "parserOutcome=SOURCE_ROWS_PRESENT selected=true disclosed=true "
            "currentStationClaim=UBL wimtReportsRunning=true accepted=true failureReason=none\n"
            "WIMT_SOAK_AUTO_ENABLE revealed=true initialChecked=false checkedAfterReset=false "
            "enableTap=true checkedAfterEnable=true enabled=true\n"
            "WIMT_SOAK_STARTED durationMinutes=30 autoIntervalMinutes=1 "
            "observationIntervalSeconds=20 expectedObservationSamples=91 "
            "initialGateAccepted=true autoStarted=true\n"
        )
        observations = []
        for index in range(EXPECTED_SAMPLES):
            elapsed = index * INTERVAL_SECONDS
            pss = 32_000 + index * 5 if elapsed % 300 == 0 else "unavailable"
            cpu = 1.0 + index / 10 if elapsed % 300 == 0 else "unavailable"
            observations.append(
                f"WIMT_SOAK_OBSERVATION sample={index} targetElapsedSeconds={elapsed} "
                f"elapsedSeconds={elapsed} jitterMs=0 selected=true visibleCells=4 "
                f"sourceRowsVisible=true summaryClass=FRESH_LABEL autoEnabled=true "
                f"receiptClock=12:00:00 receiptClass=CHECKED trackingScreen=true "
                f"appForeground=true processPssKb={pss} processCpuPercent={cpu}\n"
            )
        cycle_lines = []
        for cycle in range(1, 31):
            minute_of_day = 12 * 60 + cycle
            clock = f"{minute_of_day // 60:02d}:{minute_of_day % 60:02d}:00"
            cycle_lines.append(
                f"WIMT_SOAK_CYCLE cycle={cycle} receiptClock={clock} "
                f"elapsedSeconds={cycle * 60} elapsedMinutes={cycle} "
                "selected=true receiptSuggestsFailure=false\n"
            )
        detail = (
            "WIMT_SOAK_DETAIL sample=1 cycle=5 elapsedMinutes=5 httpStatus=200 "
            "runDateMatched=true routeMatched=true fresh=true stale=false "
            "freshnessAccurate=true rows=11 cells=4 selected=true disclosed=true "
            "currentStationClaim=UBL wimtReportsRunning=true "
            "responseClass=HTTP_200_SOURCE_ROWS_PRESENT parserOutcome=SOURCE_ROWS_PRESENT "
            "responseBytes=4321\n"
        )
        summary = (
            "WIMT_SOAK_SUMMARY durationMs=1800000 cycles=30 requiredReceipts=30 "
            "missedReceipts=0 detailSamples=1 freshSamples=1 nonFreshSamples=0 "
            "unqualifiedSamples=0 httpFailures=0 selectedLosses=0 "
            "autoStoppedAfterSourceFailure=false sourceFailureReason=none "
            "fullDuration=true finalSelected=true autoOff=true "
            "currentStationChecksPassed=true wimtRunningStatusChecksPassed=true "
            "initialGateAccepted=true autoStarted=true observationIntervalSeconds=20 "
            "observationSamples=91 expectedObservationSamples=91 missedObservationSlots=0 "
            "maxObservationJitterMs=0 processPssSampleCount=7 processPssMinKb=32000 "
            "processPssMaxKb=41000 processPssFirstKb=32000 processPssLastKb=41000 "
            "processCpuSampleCount=7 processCpuMinPercent=1.0 processCpuMaxPercent=10.0 "
            "processCpuFirstPercent=1.0 processCpuLastPercent=10.0\n"
        )
        (directory / "soak-trace.txt").write_text(
            initial + "".join(observations) + "".join(cycle_lines) + detail + summary,
                                                   encoding="utf-8")
        (directory / "instrumentation.txt").write_text("OK (1 test)\n", encoding="utf-8")
        (directory / "runner-exit.txt").write_text("instrumentationExitCode=0\n", encoding="utf-8")
        result = build_report(directory)
        assert result["passed"]
        assert result["trainNo"] == "20651"
        assert result["runDate"] == "12_Oct_2026"
        assert result["observation"]["observedSamples"] == 91
        assert result["observation"]["selectedWimtSamples"] == 91
        assert result["appProcessMemory"]["sampleCount"] == 7
        assert result["appProcessCpu"]["sampleCount"] == 7
        assert result["appProcessCpu"]["maximumPercent"] == 10.0
        assert result["autoRefresh"]["receiptElapsedSeconds"]["sampleCount"] == 30
        assert result["autoRefresh"]["receiptIntervalsSeconds"]["sampleCount"] == 29
        assert result["autoRefresh"]["receiptIntervalsSeconds"]["median"] == 60
        assert result["checks"]["allDetailedWimtResponsesFresh"]
        assert result["sourceDiagnostics"]["responseBytesMaximum"] == 4321

        # A runtime source qualification loss stops Auto, continues passive sampling, and
        # remains a failed WIMT result even when the full observation itself completes.
        runtime_observations = (
            "".join(observations[:6])
            + observations[6].replace("summaryClass=FRESH_LABEL", "summaryClass=UNQUALIFIED")
                .replace("autoEnabled=true", "autoEnabled=false")
            + "".join(line.replace("autoEnabled=true", "autoEnabled=false")
                     for line in observations[7:])
        )
        runtime_summary = (
            summary.replace("cycles=30", "cycles=1")
            .replace("missedReceipts=0", "missedReceipts=29")
            .replace("unqualifiedSamples=0", "unqualifiedSamples=1")
            .replace("autoStoppedAfterSourceFailure=false", "autoStoppedAfterSourceFailure=true")
            .replace("sourceFailureReason=none", "sourceFailureReason=UNQUALIFIED_SOURCE_RESPONSE")
        )
        runtime_trace = (
            initial + runtime_observations + "".join(cycle_lines[:1]) + detail
            + "WIMT_SOAK_AUTO_STOP reason=UNQUALIFIED_SOURCE_RESPONSE observationSample=6 autoOff=true\n"
            + runtime_summary
        )
        (directory / "soak-trace.txt").write_text(runtime_trace, encoding="utf-8")
        (directory / "instrumentation.txt").write_text("FAILURES!!!\n", encoding="utf-8")
        (directory / "runner-exit.txt").write_text("instrumentationExitCode=1\n", encoding="utf-8")
        result = build_report(directory)
        assert not result["passed"]
        assert result["failureStage"] == "WIMT_SOURCE_QUALIFICATION_FAILED"
        assert result["checks"]["full30MinuteInstalledAppObservation"]
        assert result["observation"]["sequenceAndTargetsComplete"]
        assert result["autoRefresh"]["stoppedAfterSourceFailure"]
        assert result["autoRefresh"]["sourceFailureReason"] == "UNQUALIFIED_SOURCE_RESPONSE"
        assert result["autoRefresh"]["autoOffVerified"]

        # A source failure can still produce a complete 30-minute app/UI observation,
        # but it must remain a failed WIMT result and must not enable Auto.
        passive_trace = (
            "WIMT_SOAK_TRAIN trainNo=16227 runDate=12_Oct_2026 route=MYS_TLGP\n"
            "WIMT_SOAK_INITIAL httpStatus=200 responseBytes=321 runDateMatched=true "
            "routeMatched=true fresh=false freshnessAccurate=true rows=0 cells=0 "
            "responseClass=HTTP_200_NO_QUALIFIED_ROWS parserOutcome=PARSED_ZERO_SOURCE_ACTUAL_STOPS "
            "selected=true disclosed=true currentStationClaim=unknown wimtReportsRunning=false "
            "accepted=false failureReason=NO_QUALIFIED_SOURCE_ACTUAL_ROWS\n"
            "WIMT_SOAK_AUTO_ENABLE revealed=true initialChecked=false checkedAfterReset=false "
            "enableTap=not-attempted checkedAfterEnable=false enabled=false\n"
            "WIMT_SOAK_STARTED durationMinutes=30 autoIntervalMinutes=1 observationIntervalSeconds=20 "
            "expectedObservationSamples=91 initialGateAccepted=false autoStarted=false\n"
            + "".join(observations).replace("autoEnabled=true", "autoEnabled=false")
            + "WIMT_SOAK_PASSIVE_FINAL sourceFailureReason=NO_QUALIFIED_SOURCE_ACTUAL_ROWS "
            "selected=true sourceDetailsCaptured=true appRemainedOpen=true\n"
            + "WIMT_SOAK_SUMMARY durationMs=1800000 cycles=0 requiredReceipts=30 "
            "missedReceipts=0 detailSamples=0 freshSamples=0 nonFreshSamples=1 "
            "unqualifiedSamples=2 httpFailures=0 selectedLosses=0 "
            "autoStoppedAfterSourceFailure=false sourceFailureReason=NO_QUALIFIED_SOURCE_ACTUAL_ROWS "
            "fullDuration=true finalSelected=true autoOff=true currentStationChecksPassed=false "
            "wimtRunningStatusChecksPassed=false initialGateAccepted=false autoStarted=false "
            "observationIntervalSeconds=20 observationSamples=91 expectedObservationSamples=91 "
            "missedObservationSlots=0 maxObservationJitterMs=0 processPssSampleCount=7 "
            "processPssMinKb=32000 processPssMaxKb=41000 processPssFirstKb=32000 processPssLastKb=41000\n"
        )
        (directory / "soak-trace.txt").write_text(passive_trace, encoding="utf-8")
        (directory / "instrumentation.txt").write_text("FAILURES!!!\n", encoding="utf-8")
        (directory / "runner-exit.txt").write_text("instrumentationExitCode=1\n", encoding="utf-8")
        result = build_report(directory)
        assert not result["passed"]
        assert result["failureStage"] == "INITIAL_WIMT_IN_APP_GATE_FAILED"
        assert result["observation"]["observedSamples"] == 91
        assert result["observation"]["missedSlots"] == 0
        assert "passively for 30.0 minutes" in result["failureSummary"]
        assert not result["autoRefresh"]["started"]
        assert not result["checks"]["allDetailedWimtResponsesFresh"]
        assert result["autoRefresh"]["passiveFailureReason"] == "NO_QUALIFIED_SOURCE_ACTUAL_ROWS"
        assert result["autoRefresh"]["passiveFinalSourceDetailsCaptured"]
        assert result["autoRefresh"]["appRemainedOpenAfterSourceFailure"]

        # Missing one target is not papered over by the aggregate duration.
        incomplete = passive_trace.replace(
            "WIMT_SOAK_OBSERVATION sample=45 targetElapsedSeconds=900 "
            "elapsedSeconds=900 jitterMs=0 selected=true visibleCells=4",
            "WIMT_SOAK_OBSERVATION sample=46 targetElapsedSeconds=920 "
            "elapsedSeconds=920 jitterMs=0 selected=true visibleCells=4",
        )
        (directory / "soak-trace.txt").write_text(incomplete, encoding="utf-8")
        result = build_report(directory)
        assert not result["observation"]["sequenceAndTargetsComplete"]
        assert result["failureStage"] == "INITIAL_WIMT_IN_APP_GATE_FAILED"

        # Complete target labels cannot hide an actual sample that exceeded the jitter limit.
        late_sample = passive_trace.replace(
            "sample=10 targetElapsedSeconds=200 elapsedSeconds=200 jitterMs=0",
            "sample=10 targetElapsedSeconds=200 elapsedSeconds=210 jitterMs=10001",
        )
        (directory / "soak-trace.txt").write_text(late_sample, encoding="utf-8")
        result = build_report(directory)
        assert not result["checks"]["maximumObservationJitterAtMost10Seconds"]
        assert result["observation"]["maximumJitterMs"] == 10001


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence_dir", nargs="?", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        print("WIMT 30-minute installed-app observation report self-test: PASS")
        return 0
    if args.evidence_dir is None:
        parser.error("evidence_dir is required unless --self-test is used")
    result = build_report(args.evidence_dir)
    print(json.dumps({
        "verdict": result["verdict"],
        "passed": result["passed"],
        "failureStage": result["failureStage"],
        "trainNo": result["trainNo"],
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
