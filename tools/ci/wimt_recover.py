#!/usr/bin/env python3
"""Bounded diagnosis of an existing WIMT device artifact; never makes a provider request."""
from collections import Counter
from pathlib import Path
import json
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET

CAPTURE_FILE = re.compile(
    r"^wimt-compare-[0-9]{4,5}-(before|app-live|app-details|web-cache|web-details|final)\.(png|xml)$"
)
CAPTURE_TAG = re.compile(
    r"^wimt-compare-[0-9]{4,5}-(before|app-live|app-details|web-cache|web-details|final)$"
)


def brief(log: str) -> list[str]:
    lines = []
    patterns = ("FAILURES!!!", "java.lang.AssertionError", "java.lang.IllegalStateException",
                "no known unambiguously active", "INSTRUMENTATION_FAILED",
                "INSTRUMENTATION_RESULT:", "Tests run:", "Process crashed", "OK (", "AssertionError:",
                "Process completed with exit code", "FAILURE:")
    for line in log.splitlines():
        if any(k in line for k in patterns):
            # This test's assertions include public UI labels only; still do not
            # print arbitrary stack frames, URLs, session headers or CDN IDs.
            clean = re.sub(r"(?i)(x-hcdn-request-id|cdnRequestId|cookie|token)[=: ]+\S+",
                           r"\1=<redacted>", line)
            clean = re.sub(r"https?://\S+", "<url>", clean)[:280]
            lines.append(clean)
    return lines[-18:]


UI_DIAGNOSTIC_IDS = frozenset({
    "trainNo", "routeLabel", "runDate", "sourceSpinner", "headline", "heroEyebrow",
    "provenance", "age", "lastUpdated", "mirrorState", "routeHeadActual",
    "sourceSummary", "connectionAlert", "note", "delay", "next",
})


def _ui_hierarchy(path: Path) -> dict:
    """Extract only whitelisted TrackTrain labels from an in-test hierarchy."""
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError):
        return {}
    result = {}
    for node in root.iter("node"):
        if node.get("package") != "in.opentrack.india":
            continue
        resource = node.get("resource-id", "").rsplit("/", 1)[-1]
        if resource not in UI_DIAGNOSTIC_IDS:
            continue
        item = {}
        text = node.get("text", "").strip()
        description = node.get("content-desc", "").strip()
        if text:
            item["text"] = text[:180]
        if description:
            item["contentDescription"] = description[:240]
        if item:
            result.setdefault(resource, []).append(item)
    return result


def _read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def _read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def summarize_live_artifact(folder: Path, original: dict,
                            source_run: str = "unknown",
                            source_artifact: str = "unknown") -> dict:
    """Summarize a `.in` comparison artifact without publishing screenshot bytes or raw rows."""
    raw_attempts = original.get("attempts", [])
    if not isinstance(raw_attempts, list):
        raw_attempts = []
    attempts = [item for item in raw_attempts if isinstance(item, dict)]
    verification = original.get("screenshotVerification")
    if not isinstance(verification, dict):
        verification = _read_json(folder / "screen-verification.json") or {}

    expected_names = set()
    for attempt in attempts:
        names = attempt.get("screenshots", [])
        if not isinstance(names, list):
            continue
        for tag in names:
            if isinstance(tag, str) and CAPTURE_TAG.fullmatch(tag):
                expected_names.update((tag + ".png", tag + ".xml"))

    capture_paths = [path for path in folder.rglob("*")
                     if path.is_file() and CAPTURE_FILE.fullmatch(path.name)]
    paths_by_name: dict[str, list[Path]] = {}
    for path in capture_paths:
        paths_by_name.setdefault(path.name, []).append(path)
    found_expected = {
        name for name in expected_names
        if any(path.stat().st_size > 0 for path in paths_by_name.get(name, []))
    }
    png_expected = {name for name in expected_names if name.endswith(".png")}
    xml_expected = {name for name in expected_names if name.endswith(".xml")}
    png_found = {name for name in found_expected if name.endswith(".png")}
    xml_found = {name for name in found_expected if name.endswith(".xml")}

    recovery = _read_json(folder / "capture-recovery.json")
    if not isinstance(recovery, dict):
        candidate = verification.get("captureRecovery")
        recovery = candidate if isinstance(candidate, dict) else None
    recovery_keys = (
        "appPrivateEvidenceListingSucceeded", "expectedScreenshotFileCount",
        "listedScreenshotFileCount", "expectedFilesFoundInAppDirectory",
        "recoveredScreenshotFileCount", "expectedFilesRecovered",
        "missingExpectedScreenshotFileCount", "extraListedScreenshotFileCount",
        "failedScreenshotCopyCount", "captureAccessError",
    )
    recovery_summary = ({key: recovery.get(key) for key in recovery_keys}
                        if recovery is not None else None)

    listing_path = folder / "capture-listing.txt"
    listed = set()
    if listing_path.is_file():
        listed = {line.strip() for line in listing_path.read_text(
            encoding="utf-8", errors="replace").splitlines()
            if CAPTURE_FILE.fullmatch(line.strip())}
    transfers_path = folder / "capture-transfers.tsv"
    transfer_counts: Counter[str] = Counter()
    if transfers_path.is_file():
        for line in transfers_path.read_text(encoding="utf-8", errors="replace").splitlines():
            parts = line.split("\t")
            if len(parts) >= 2:
                transfer_counts[parts[1]] += 1

    diagnostic_attempts = verification.get("diagnosticOnlyAttempts", [])
    if not isinstance(diagnostic_attempts, list):
        diagnostic_attempts = []
    diagnostic_captures = []
    for attempt in diagnostic_attempts:
        if not isinstance(attempt, dict):
            continue
        captures = attempt.get("captures", [])
        if isinstance(captures, list):
            diagnostic_captures.extend(capture for capture in captures
                                       if isinstance(capture, dict))
    verdicts = Counter(str(attempt.get("verdict", "UNKNOWN")) for attempt in attempts)
    ages = [attempt.get("webFreshestRowAgeSeconds") for attempt in attempts
            if isinstance(attempt.get("webFreshestRowAgeSeconds"), int) and
            not isinstance(attempt.get("webFreshestRowAgeSeconds"), bool)]
    slim_attempts = [{
        "trainNo": attempt.get("trainNo"),
        "serviceDate": attempt.get("serviceDate"),
        "verdict": attempt.get("verdict"),
        "directFresh": attempt.get("directFresh"),
        "webFresh": attempt.get("webFresh"),
        "webFreshestRowAgeSeconds": attempt.get("webFreshestRowAgeSeconds"),
        "webSourceRows": attempt.get("webSourceRows"),
        "overlappingEventFields": attempt.get("overlappingEventFields"),
    } for attempt in attempts]
    failed_step_log = _read_text(folder / "failed-step-log.txt")
    instrumentation_log = _read_text(folder / "instrumentation.txt")
    recorded_log_status = _read_text(folder / "failed-step-log-status.txt").strip()
    if recorded_log_status not in ("available", "unavailable"):
        recorded_log_status = "not-recorded"

    if recovery_summary is not None:
        diagnosis = (
            "The artifact contains the run's explicit app-private listing and copy summary; "
            "use those counts to distinguish on-device capture from recovery."
        )
    elif listing_path.is_file() or transfers_path.is_file():
        diagnosis = (
            "A listing or transfer ledger exists, but this artifact has no normalized recovery "
            "summary; inspect the recorded on-device names and copy statuses."
        )
    else:
        diagnosis = (
            "This artifact has no app-private capture listing or per-file transfer ledger. "
            "Missing/invalid recovered images alone cannot establish whether captures were absent "
            "on-device or lost during post-test recovery."
        )

    return {
        "sourceRun": source_run,
        "sourceArtifact": source_artifact,
        "accepted": original.get("accepted"),
        "verdict": original.get("verdict"),
        "completedComparisons": original.get("completedComparisons"),
        "minimumComparisons": original.get("minimumComparisons"),
        "installedApkTestPassed": original.get("installedApkTestPassed"),
        "inAppScreensVerified": original.get("inAppScreensVerified"),
        "attemptCount": len(attempts),
        "attemptVerdicts": dict(sorted(verdicts.items())),
        "attempts": slim_attempts,
        "webRowAgeSecondsRange": [min(ages), max(ages)] if ages else None,
        "screenshotVerification": {
            "status": verification.get("verificationStatus"),
            "verified": verification.get("verified"),
            "acceptedComparisonCount": verification.get("acceptedComparisonCount"),
            "minimumComparisons": verification.get("minimumComparisons"),
            "diagnosticAttemptCount": len(diagnostic_attempts),
            "diagnosticCaptureCount": len(diagnostic_captures),
            "validPngAndAppHierarchyCount": sum(
                capture.get("pngAndAppHierarchyValid") is True
                for capture in diagnostic_captures),
            "contextCheckedCaptureCount": sum(
                capture.get("diagnosticValid") is True for capture in diagnostic_captures),
        },
        "failedStepLog": {
            "retrievalStatus": recorded_log_status,
            "boundedFailureLines": brief(failed_step_log),
            "boundedInstrumentationLines": brief(instrumentation_log),
        },
        "artifactCaptureFiles": {
            "pngFiles": sum(path.suffix == ".png" for path in capture_paths),
            "xmlFiles": sum(path.suffix == ".xml" for path in capture_paths),
            "expectedPngFiles": len(png_expected),
            "expectedPngFilesPresentAndNonempty": len(png_expected & png_found),
            "expectedXmlFiles": len(xml_expected),
            "expectedXmlFilesPresentAndNonempty": len(xml_expected & xml_found),
            "missingExpectedFileCount": len(expected_names - found_expected),
        },
        "recoveryLedger": {
            "captureListingPresent": listing_path.is_file(),
            "captureListingFileCount": len(listed),
            "captureTransfersPresent": transfers_path.is_file(),
            "transferStatuses": dict(sorted(transfer_counts.items())),
            "captureRecovery": recovery_summary,
        },
        "captureDiagnosis": diagnosis,
        "reason": str(original.get("reason", ""))[:700],
    }


def summarize_direct_source_artifact(folder: Path, original: dict,
                                     source_run: str = "unknown",
                                     source_artifact: str = "unknown") -> dict:
    """Recover only redacted metadata from a direct-source observer artifact."""
    observation = original.get("observation")
    if not isinstance(observation, dict):
        observation = {}
    raw_samples = observation.get("samples", [])
    if not isinstance(raw_samples, list):
        raw_samples = []
    sample_fields = (
        "checkpointMinute", "elapsedSeconds", "sampledAtUtc", "httpStatus",
        "responseBytes", "responseSha256", "runDateMatched", "routeMatched",
        "freshSourceStamp", "sourceUpdateStamp", "sourceUpdateAgeSeconds",
        "currentStationCodeClaim", "sourceScheduleRowCount", "sourceActualStopRowCount",
        "runningStatusQualified", "protocolChecksPassed", "qualified", "failureReasons",
    )
    samples = []
    for item in raw_samples:
        if not isinstance(item, dict):
            continue
        samples.append({key: item.get(key) for key in sample_fields if key in item})
    raw_checks = observation.get("checks")
    checks = ({str(key)[:120]: value for key, value in raw_checks.items()
               if isinstance(key, str) and type(value) is bool}
              if isinstance(raw_checks, dict) else {})
    raw_attempts = original.get("candidateAttempts", [])
    if not isinstance(raw_attempts, list):
        raw_attempts = []
    attempt_fields = (
        "probe", "trainNo", "runDate", "route", "httpStatus", "responseBytes",
        "responseSha256", "runDateMatched", "routeMatched", "freshSourceStamp",
        "currentStationCodeClaim", "sourceActualStopRowCount", "qualified", "failureReasons",
    )
    attempts = [{key: item.get(key) for key in attempt_fields if key in item}
                for item in raw_attempts if isinstance(item, dict)]
    return {
        "sourceRun": source_run,
        "sourceArtifact": source_artifact,
        "mode": "direct-source-only" if original.get("mode") == "direct-source-only" else "unknown",
        "source": original.get("source") if original.get("source") ==
            "whereismytrain.in/cache/live_status" else None,
        "passed": original.get("passed") is True,
        "verdict": str(original.get("verdict", ""))[:100],
        "trainNo": original.get("trainNo") if isinstance(original.get("trainNo"), str)
            and re.fullmatch(r"[0-9]{1,8}", original.get("trainNo")) else None,
        "runDate": str(original.get("runDate", ""))[:40],
        "route": str(original.get("route", ""))[:100],
        "selectionStopReason": str(original.get("selectionStopReason", ""))[:120],
        "durationMinutes": observation.get("durationMinutes")
            if isinstance(observation.get("durationMinutes"), (int, float)) and
               type(observation.get("durationMinutes")) is not bool else None,
        "requiredDurationMinutes": observation.get("requiredDurationMinutes")
            if type(observation.get("requiredDurationMinutes")) is int else 45,
        "recordedCheckpointMinutes": [value for value in
            observation.get("recordedCheckpointMinutes", []) if type(value) is int]
            if isinstance(observation.get("recordedCheckpointMinutes"), list) else [],
        "checks": checks,
        "candidateAttempts": attempts,
        "samples": samples,
        "reportFilePresent": (folder / "report.md").is_file(),
        "noProviderRequestDuringRecovery": True,
        "independentlyVerifiedMovement": False,
        "gpsVerified": False,
    }


def _run_direct_source_report(folder: Path, original: dict) -> int:
    source_run = os.environ.get("WIMT_SOURCE_RUN", "unknown")
    source_artifact = os.environ.get("WIMT_SOURCE_ARTIFACT", "unknown")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,100}", source_artifact):
        source_artifact = "invalid-artifact-name"
    summary = summarize_direct_source_artifact(folder, original, source_run, source_artifact)
    original_report = original.get("reportMarkdown")
    if not isinstance(original_report, str) or not original_report.strip():
        original_report = _read_text(folder / "report.md")
    text = [
        "### Existing direct WIMT `.in` observation · artifact recovery (no new provider request)",
        "",
        f"- Source run/artifact: `{source_run}` / `{source_artifact}`",
        f"- Timed observation passed: **{summary['passed']}**",
        f"- Duration: **{summary['durationMinutes'] if summary['durationMinutes'] is not None else 'not recorded'} / 45 minutes**",
        "- This was direct-source evidence only; recovery made no WIMT request and did not convert it into app/emulator evidence.",
        "",
        "```json",
        json.dumps(summary, indent=2, sort_keys=True),
        "```",
        "",
        original_report.strip() if original_report.strip() else "No original markdown report was present in the artifact.",
        "",
    ]
    report = "\n".join(text)
    (folder / "recovered-report.md").write_text(report, encoding="utf-8")
    print(report)
    return 0


def _is_wimt_46_result(original) -> bool:
    return isinstance(original, dict) and \
        type(original.get("requiredDurationMinutes")) is int and \
        original.get("requiredDurationMinutes") == 46 and \
        isinstance(original.get("checks"), dict) and \
        isinstance(original.get("refresh"), dict)


def _safe_trace_events(folder: Path) -> list[dict]:
    """Keep only allowlisted fields emitted by the 46-minute instrumentation test."""
    allowed_events = {
        "WIMT_SOAK_TRAIN", "WIMT_SOAK_TABLE_CAPTURE", "WIMT_SOAK_INITIAL",
        "WIMT_SOAK_AUTO_ENABLE", "WIMT_SOAK_STARTED", "WIMT_SOAK_SAMPLE",
        "WIMT_SOAK_DETAIL", "WIMT_SOAK_AUTO_STOP", "WIMT_SOAK_SOURCE_LOST",
        "WIMT_SOAK_DETAIL_UNAVAILABLE", "WIMT_SOAK_FINAL_DETAILS_UNAVAILABLE",
        "WIMT_SOAK_MISSED_SLOT", "WIMT_SOAK_APP_SCREEN_LOST", "WIMT_SOAK_SUMMARY",
    }
    allowed_fields = {
        "trainNo", "runDate", "route", "selection", "currentStationRequired",
        "selected", "visibleCells", "hasSourceRows", "httpStatus", "responseBytes",
        "runDateMatched", "routeMatched", "fresh", "stale", "freshnessAccurate",
        "wimtReportsRunning", "runningStatusRequired", "wimtRunningStatusChecksPassed",
        "rows", "cells", "responseClass", "parserOutcome", "disclosed",
        "currentStationClaim", "accepted", "revealed", "initialChecked", "resetTap",
        "checkedAfterReset", "enableTap", "enableRetryTap", "checkedAfterEnable",
        "enabled", "durationMinutes", "autoIntervalMinutes", "requiredReceipts",
        "initialHttpStatus", "minute", "elapsedSeconds", "receiptClock", "autoEnabled",
        "sourceRowsVisible", "receiptFailure", "sample", "cycle", "elapsedMinutes",
        "autoOff", "reason", "elapsedMs", "slot", "detailSamples", "freshSamples",
        "nonFreshSamples", "unqualifiedSamples", "httpFailures", "selectedLosses",
        "autoStoppedAfterSourceFailure", "sourceFailureReason", "durationMs", "cycles",
        "missedReceipts", "fullDuration", "finalSelected", "currentStationChecksPassed",
    }
    boolean_fields = {
        "currentStationRequired", "selected", "hasSourceRows", "runDateMatched",
        "routeMatched", "fresh", "stale", "freshnessAccurate", "wimtReportsRunning",
        "runningStatusRequired", "wimtRunningStatusChecksPassed", "disclosed", "accepted",
        "revealed", "resetTap", "enabled", "sourceRowsVisible", "autoEnabled",
        "receiptFailure", "autoOff", "autoStoppedAfterSourceFailure", "fullDuration",
        "finalSelected", "currentStationChecksPassed",
    }
    numeric_fields = {
        "trainNo", "responseBytes", "rows", "cells", "durationMinutes",
        "autoIntervalMinutes", "requiredReceipts", "initialHttpStatus", "minute",
        "elapsedSeconds", "sample", "cycle", "elapsedMinutes", "elapsedMs", "slot",
        "detailSamples", "freshSamples", "nonFreshSamples", "unqualifiedSamples",
        "httpFailures", "selectedLosses", "durationMs", "cycles", "missedReceipts",
    }
    events = []
    trace = _read_text(folder / "soak-trace.txt")
    for line in trace.splitlines():
        tag, separator, payload = line.partition(" ")
        if not separator or tag not in allowed_events:
            continue
        values = dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)=([^\s]+)", payload))
        safe_values = {}
        for key, value in values.items():
            if key not in allowed_fields:
                continue
            if key in boolean_fields:
                if value.lower() in ("true", "false"):
                    safe_values[key] = value.lower() == "true"
            elif key in numeric_fields:
                if re.fullmatch(r"-?[0-9]{1,16}", value):
                    safe_values[key] = int(value)
            elif key == "currentStationClaim":
                if re.fullmatch(r"(?i:unknown|[A-Z0-9]{2,5})", value):
                    safe_values[key] = value.upper()
            elif key == "receiptClock":
                if re.fullmatch(r"(?:[0-2][0-9]:[0-5][0-9]:[0-5][0-9]|none)", value):
                    safe_values[key] = value
            elif re.fullmatch(r"[A-Za-z0-9_.:/-]{1,120}", value):
                safe_values[key] = value
        # Minute-by-minute receipt samples are already summarized in result.json;
        # retain a few boundary samples plus every five-minute source-details check.
        if tag == "WIMT_SOAK_SAMPLE" and safe_values.get("minute") not in (0, 1, 45, 46):
            continue
        if tag == "WIMT_SOAK_DETAIL":
            cycle = values.get("cycle", "")
            if cycle != "final" and (not cycle.isdigit() or int(cycle) % 5):
                continue
        if tag == "WIMT_SOAK_MISSED_SLOT" and len(events) >= 32:
            continue
        events.append({"event": tag, "fields": safe_values})
        if len(events) >= 100:
            break
    return events


def summarize_wimt_46_artifact(folder: Path, original: dict,
                               source_run: str = "unknown",
                               source_artifact: str = "unknown") -> dict:
    """Recover bounded 46-minute evidence without repeating a WIMT request."""
    if not re.fullmatch(r"[0-9]{1,20}", source_run):
        source_run = "unknown"
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,100}", source_artifact):
        source_artifact = "unknown"
    initial = original.get("initial")
    if not isinstance(initial, dict):
        initial = {}
    refresh = original.get("refresh")
    if not isinstance(refresh, dict):
        refresh = {}
    raw_samples = refresh.get("samples", [])
    samples = [item for item in raw_samples if isinstance(item, dict)] \
        if isinstance(raw_samples, list) else []
    checkpoints = original.get("currentStationCheckpoints", [])
    if not isinstance(checkpoints, list):
        checkpoints = []
    safe_checkpoints = []
    for item in checkpoints[:20]:
        if not isinstance(item, dict):
            continue
        station = item.get("currentStationCodeClaim")
        safe_checkpoints.append({
            "minute": item.get("minute") if type(item.get("minute")) is int else None,
            "currentStationCodeClaim": station.upper()
                if isinstance(station, str) and re.fullmatch(r"(?i:[A-Z0-9]{2,5})", station)
                else None,
            "httpStatus": item.get("httpStatus") if type(item.get("httpStatus")) is int else None,
            "runDateMatched": item.get("runDateMatched") is True,
            "routeMatched": item.get("routeMatched") is True,
            "freshSourceStamp": item.get("freshSourceStamp") is True,
            "sourceRows": item.get("sourceRows") if type(item.get("sourceRows")) is int else None,
            "wimtRunningStatusQualified": item.get("wimtRunningStatusQualified") is True,
            "qualified": item.get("qualified") is True,
        })
    checks = original.get("checks")
    if not isinstance(checks, dict):
        checks = {}
    safe_checks = {str(key)[:120]: value for key, value in checks.items()
                   if isinstance(key, str) and type(value) is bool}
    screenshot_names = []
    raw_screenshots = original.get("screenshots", [])
    if isinstance(raw_screenshots, list):
        screenshot_names = sorted({
            value.rsplit("/", 1)[-1] for value in raw_screenshots
            if isinstance(value, str) and
            re.fullmatch(r"screens/wimt-soak-[A-Za-z0-9_-]{1,80}\.png", value)
        })
    instrumentation = _read_text(folder / "instrumentation.txt")
    failed_step_log = _read_text(folder / "failed-step-log.txt")
    retrieval_status = _read_text(folder / "failed-step-log-status.txt").strip()
    if retrieval_status not in ("available", "unavailable"):
        retrieval_status = "not-recorded"

    train_no = original.get("trainNo")
    run_date = original.get("runDate")
    route = original.get("route")
    station_claim = initial.get("currentStationCodeClaim")
    safe_station_claim = (station_claim.upper() if isinstance(station_claim, str) and
                          re.fullmatch(r"(?i:unknown|[A-Z0-9]{2,5})", station_claim) else None)
    running_gate = safe_checks.get("wimtRunningStatusQualifiedAtStartAndCheckpoints")
    running_status = (
        "APP_QUALIFIED_FROM_FRESH_MATCHED_CURRENT_STATION_AND_ACTUAL_ROWS; not movement proof"
        if running_gate is True else
        "NOT_QUALIFIED_BY_THE_APP_RUNNING_STATUS_GATE" if running_gate is False else
        "NOT_CAPTURED_BY_THIS_RUN_SCHEMA"
    )
    fresh_stamp = initial.get("freshSourceStamp") is True
    freshness_accurate = initial.get("freshnessAccurate") is True
    freshness_assessment = (
        "FRESH_SOURCE_STAMP_REPORTED" if fresh_stamp and freshness_accurate else
        "NONFRESH_SOURCE_STAMP_REPORTED" if freshness_accurate else
        "FRESHNESS_NOT_ESTABLISHED_OR_INCONSISTENT"
    )
    trace_events = _safe_trace_events(folder)
    source_loss_events = [
        {"event": event["event"], **event["fields"]}
        for event in trace_events
        if event["event"] in (
            "WIMT_SOAK_SOURCE_LOST", "WIMT_SOAK_DETAIL_UNAVAILABLE",
            "WIMT_SOAK_FINAL_DETAILS_UNAVAILABLE", "WIMT_SOAK_AUTO_STOP",
        )
    ]
    running_required = any(
        event["fields"].get(key) is True
        for event in trace_events
        for key in ("currentStationRequired", "runningStatusRequired")
    )
    running_failure_points = []
    if running_required:
        for event in trace_events:
            if event["event"] not in ("WIMT_SOAK_INITIAL", "WIMT_SOAK_DETAIL"):
                continue
            item = event["fields"]
            if item.get("wimtReportsRunning") is True:
                continue
            status = item.get("httpStatus")
            if isinstance(status, str) and re.fullmatch(r"[1-5][0-9]{2}", status):
                status = int(status)
            if type(status) is not int:
                status = None
            running_failure_points.append({
                "event": event["event"],
                "cycle": item.get("cycle"),
                "elapsedMinutes": item.get("elapsedMinutes"),
                "httpStatus": status,
                "runDateMatched": item.get("runDateMatched") is True,
                "routeMatched": item.get("routeMatched") is True,
                "freshSourceStamp": item.get("fresh") is True,
                "sourceRows": item.get("rows") if type(item.get("rows")) is int else None,
                "currentStationCodeClaim": item.get("currentStationClaim"),
                "appRunningStatusQualified": False,
            })
    result = {
        "verdict": str(original.get("verdict", ""))[:100],
        "passed": original.get("passed") is True,
        "failureStage": str(original.get("failureStage", ""))[:100],
        "requiredDurationMinutes": 46,
        "observedDurationMs": original.get("observedDurationMs")
            if type(original.get("observedDurationMs")) is int else None,
        "observedDurationMinutes": original.get("observedDurationMinutes")
            if isinstance(original.get("observedDurationMinutes"), (int, float)) and
               type(original.get("observedDurationMinutes")) is not bool else None,
        "trainNo": train_no if isinstance(train_no, str) and re.fullmatch(r"[0-9]{1,8}", train_no)
            else None,
        "runDate": run_date if isinstance(run_date, str) and
            re.fullmatch(r"[A-Za-z0-9 _-]{1,40}", run_date) else None,
        "route": route if isinstance(route, str) and
            re.fullmatch(r"[A-Za-z0-9 _>→.-]{1,100}", route) else None,
        "trainSelectionRule": str(original.get("trainSelectionRule", ""))[:100],
        "source": "whereismytrain.in/cache/live_status (installed APK selected-source response)",
        "initial": {
            "accepted": initial.get("accepted") is True,
            "httpStatus": initial.get("httpStatus") if type(initial.get("httpStatus")) is int else None,
            "runDateMatched": initial.get("runDateMatched") is True,
            "routeMatched": initial.get("routeMatched") is True,
            "freshSourceStamp": fresh_stamp,
            "freshnessAccurate": freshness_accurate,
            "freshnessAssessment": freshness_assessment,
            "sourceRows": initial.get("sourceRows") if type(initial.get("sourceRows")) is int else None,
            "visibleSourceCells": initial.get("visibleSourceCells")
                if type(initial.get("visibleSourceCells")) is int else None,
            "currentStationCodeClaim": safe_station_claim,
            "wimtRunningStatusQualified": initial.get("wimtRunningStatusQualified") is True,
        },
        "refresh": {
            "receipts": refresh.get("receipts") if type(refresh.get("receipts")) is int else 0,
            "requiredReceipts": refresh.get("requiredReceipts")
                if type(refresh.get("requiredReceipts")) is int else None,
            "missedReceiptSlots": refresh.get("missedReceiptSlots")
                if type(refresh.get("missedReceiptSlots")) is int else None,
            "sampleCount": len(samples),
            "sampleMinutes": sorted({item.get("minute") for item in samples
                                      if type(item.get("minute")) is int}),
            "failedReceiptMinutes": sorted({item.get("minute") for item in samples
                                             if type(item.get("minute")) is int and
                                             item.get("receiptFailure") is True}),
        },
        "currentStationCheckpoints": safe_checkpoints,
        "checks": safe_checks,
        "independentlyVerifiedMovement": False,
        "gpsVerified": False,
        "runningStatus": running_status,
        "runningStatusBasis": "app-side rule: matched run/route + fresh source update + current-station field + at least one source-reported actual stop clock; not an explicit provider boolean or movement/GPS proof",
        "runningStatusFailurePoints": running_failure_points,
        "sourceLossEvents": source_loss_events,
        "instrumentationPassed": original.get("instrumentationPassed") is True,
        "runnerExitCode": original.get("runnerExitCode")
            if type(original.get("runnerExitCode")) is int else None,
        "reportedScreenshotNames": screenshot_names,
        "screenshotFilesVerified": False,
        "traceEvents": trace_events,
        "failedStepLog": {
            "retrievalStatus": retrieval_status,
            "boundedFailureLines": brief(failed_step_log),
            "boundedInstrumentationLines": brief(instrumentation),
        },
        "note": "Recovered from the completed run artifact and GitHub log only; no WIMT request was made.",
    }
    return {"sourceRun": source_run, "sourceArtifact": source_artifact, **result}


def _run_wimt_46_report(folder: Path, original: dict) -> int:
    source_run = os.environ.get("WIMT_SOURCE_RUN", "unknown")
    source_artifact = os.environ.get("WIMT_SOURCE_ARTIFACT", "unknown")
    summary = summarize_wimt_46_artifact(folder, original, source_run, source_artifact)
    report = [
        "### Existing installed-APK WIMT `.in` 46-minute run · recovery diagnosis (no new provider request)",
        "", "```json", json.dumps(summary, indent=2, sort_keys=True), "```", "",
        "The running-status result is an app-side qualification requiring a fresh matched WIMT `.in` "
        "response, current-station field, and at least one source-reported actual stop clock; it is "
        "not an explicit provider boolean or independent movement/GPS proof. A failed run is not a "
        "completed observation. Screenshot filenames are copied from result metadata only; the "
        "recovery step does not validate image bytes or matching app hierarchies.",
    ]
    text = "\n".join(report) + "\n"
    (folder / "recovered-report.md").write_text(text, encoding="utf-8")
    print(text)
    return 0


def _is_live_comparison_result(original) -> bool:
    return isinstance(original, dict) and \
        isinstance(original.get("attempts"), list) and \
        isinstance(original.get("screenshotVerification"), dict) and \
        any(isinstance(attempt, dict) and isinstance(attempt.get("trainNo"), str)
            for attempt in original["attempts"])


def summarize_website_artifact(folder: Path, original: dict, source_run: str,
                               source_artifact: str) -> dict:
    """Recover a bounded `.org.in` device verdict without exposing raw diary or screenshots."""
    if not re.fullmatch(r"[0-9]{1,20}", source_run):
        source_run = "unknown"
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,100}", source_artifact):
        source_artifact = "unknown"
    instrumentation = _read_text(folder / "instrumentation.txt")
    if not original.get("accepted") and (not original.get("wireReceiptAvailable", False) or
                                           original.get("websiteGetCount") == 0) and instrumentation:
        from wimt_device_report import verdict
        result = verdict(instrumentation, "", 1)
    else:
        result = original
    safe_fields = (
        "verdict", "accepted", "instrumentationPassed", "websiteGetCount", "websiteAnswered",
        "websiteRefused", "wireReceiptAvailable", "source", "sourceTransport",
        "acceptedMode", "siteRows", "detachedSiteClaims",
        "trainNumber", "originDay", "originRunDateIst", "runDateBasis", "runIdentity",
        "siteRoute", "sitePageEndpoints", "sitePageLabels", "sitePageRunDate", "siteDateMatched",
        "siteShape", "selectedRoute", "selectedRouteStopCount", "siteParserState",
        "routeExtentState", "routeExtentHaltCount", "siteCardContainers",
        "liveIdentityRouteMatch", "siteClaimStation",
        "siteClaimKind", "siteClaimMinuteFromOrigin", "siteClaimTimeBasis", "siteClaimTimeIst",
        "siteClaimAgeSecondsAtCheck", "pagePublishedAtIst", "pageAgeSecondsAtCheck",
        "checkedAtIst", "provenanceComplete", "googlePhoneAppFeed", "browserEngineRequest",
        "websiteGetCountScope", "note",
    )
    safe_result = {key: result[key] for key in safe_fields if key in result}
    strict_screens = _read_json(folder / "screen-verification.json") or {}
    diagnostic_screens = _read_json(folder / "screen-verification-diagnostic.json") or {}
    verified = strict_screens.get("verifiedAppScreens", {})
    if not isinstance(verified, dict):
        verified = {}
    diagnostic_captures = diagnostic_screens.get("verifiedAppScreens", {})
    if not isinstance(diagnostic_captures, dict):
        diagnostic_captures = {}
    screens = strict_screens or diagnostic_screens
    failed_log = _read_text(folder / "failed-step-log.txt")
    hierarchy_names = (
        "wimt-settings-diagnostics", "wimt-search-route-criteria", "wimt-search-route-results",
        "wimt-before-selected-source", "wimt-source-picker-open", "wimt-after-selected-source")
    captured_hierarchies = {}
    for name in hierarchy_names:
        path = folder / "screens" / f"{name}.xml"
        if path.is_file():
            captured_hierarchies[name] = _ui_hierarchy(path)
    return {
        "sourceRun": source_run,
        "sourceArtifact": source_artifact,
        "result": safe_result,
        "screenVerificationFilePresent": bool(strict_screens),
        "verifiedInAppScreenshotNames": sorted(verified),
        "diagnosticScreenVerificationFilePresent": bool(diagnostic_screens),
        "diagnosticInAppScreenshotNames": sorted(diagnostic_captures),
        "capturedInTestHierarchyNames": sorted(captured_hierarchies),
        "whitelistedUiHierarchySummaries": captured_hierarchies,
        "displayedRefusalRequired": screens.get("displayedRefusalRequired"),
        "screenVerificationMode": screens.get("verificationMode", "UNAVAILABLE"),
        "failedStepLogRetrieval": _read_text(
            folder / "failed-step-log-status.txt").strip() or "not-recorded",
        "boundedFailedStepLines": brief(failed_log),
        "boundedInstrumentationLines": brief(instrumentation),
        "note": "Recovery reads this completed run only; no app reinstall or website request is made.",
    }


def _run_website_report(folder: Path, original: dict) -> int:
    source_run = os.environ.get("WIMT_SOURCE_RUN", "unknown")
    source_artifact = os.environ.get("WIMT_SOURCE_ARTIFACT", "unknown")
    summary = summarize_website_artifact(folder, original, source_run, source_artifact)
    detached = summary.get("result", {}).get("acceptedMode") == \
        "DETACHED_SITE_CLAIM_DIAGNOSTIC_ONLY"
    caveat = (
        "The page yielded a detached site-claim diagnostic whose route matched the fresh live identity; "
        "it is NOT accepted for the selected timetable because the route/date did not match or verify. "
        "No timetable row, actual, or current position was inferred."
        if detached else
        "The event time remains a website-published claim; the service date is inferred from the "
        "selected timetable because the page is undated. A failure or missing screenshot is not acceptance."
    )
    report = [
        "### Existing installed-APK `.org.in` run · recovery diagnosis (no new website request)",
        "", "```json", json.dumps(summary, indent=2, sort_keys=True), "```", "", caveat,
    ]
    text = "\n".join(report) + "\n"
    (folder / "recovered-report.md").write_text(text, encoding="utf-8")
    print(text)
    return 0


def _self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="wimt-recover-") as temporary:
        folder = Path(temporary)
        (folder / "screens").mkdir()
        attempt = {
            "trainNo": "12301", "serviceDate": "12 Oct 2026",
            "verdict": "WEB_CACHE_SOURCE_NOT_QUALIFIED", "directFresh": True,
            "webFresh": False, "webFreshestRowAgeSeconds": 901,
            "webSourceRows": 2, "overlappingEventFields": 0,
            "screenshots": ["wimt-compare-12301-app-live"],
        }
        (folder / "screens" / "wimt-compare-12301-app-live.png").write_bytes(b"png")
        (folder / "failed-step-log.txt").write_text(
            "Process completed with exit code 1.\n", encoding="utf-8")
        (folder / "failed-step-log-status.txt").write_text("available\n", encoding="utf-8")
        (folder / "instrumentation.txt").write_text(
            "FAILURES!!!\njava.lang.AssertionError: expected three comparisons\n",
            encoding="utf-8")
        result = {
            "accepted": False,
            "verdict": "INSUFFICIENT_FRESH_SAME_DATE_COMPARISONS",
            "completedComparisons": 0, "minimumComparisons": 3,
            "installedApkTestPassed": False, "inAppScreensVerified": False,
            "attempts": [attempt],
            "screenshotVerification": {
                "verificationStatus": "NO_COMPLETED_COMPARISONS_TO_VERIFY",
                "verified": False, "acceptedComparisonCount": 0,
                "minimumComparisons": 3,
                "diagnosticOnlyAttempts": [{"captures": [{
                    "pngAndAppHierarchyValid": True, "diagnosticValid": True,
                }]}],
            },
        }
        summary = summarize_live_artifact(folder, result, "123", "test-artifact")
        assert summary["attemptVerdicts"] == {"WEB_CACHE_SOURCE_NOT_QUALIFIED": 1}
        assert summary["webRowAgeSecondsRange"] == [901, 901]
        assert summary["screenshotVerification"]["validPngAndAppHierarchyCount"] == 1
        assert summary["artifactCaptureFiles"]["expectedPngFilesPresentAndNonempty"] == 1
        assert summary["artifactCaptureFiles"]["expectedXmlFilesPresentAndNonempty"] == 0
        assert "no app-private capture listing" in summary["captureDiagnosis"]
        assert summary["failedStepLog"]["retrievalStatus"] == "available"
        assert "Process completed with exit code" in \
            summary["failedStepLog"]["boundedFailureLines"][0]
        assert "FAILURES!!!" in \
            summary["failedStepLog"]["boundedInstrumentationLines"][0]
    with tempfile.TemporaryDirectory(prefix="wimt-46-recover-") as temporary:
        folder = Path(temporary)
        (folder / "soak-trace.txt").write_text(
            "WIMT_SOAK_TRAIN trainNo=16227 runDate=12_Oct_2026 route=MYS_TLGP "
            "selection=IST-hour-schedule-candidate currentStationRequired=true\n"
            "WIMT_SOAK_INITIAL httpStatus=200 responseBytes=1234 runDateMatched=true "
            "routeMatched=true fresh=true freshnessAccurate=true rows=2 cells=3 "
            "responseClass=HTTP_200_SOURCE_ROWS_PRESENT parserOutcome=SOURCE_ROWS_PRESENT "
            "selected=true disclosed=true currentStationClaim=MYS "
            "wimtReportsRunning=false accepted=false\n"
            "WIMT_SOAK_STARTED durationMinutes=46 autoIntervalMinutes=1 "
            "requireCurrentStation=true requiredReceipts=46 initialHttpStatus=200\n"
            "WIMT_SOAK_SAMPLE minute=0 elapsedSeconds=0 receiptClock=12:00:00 "
            "selected=true visibleCells=3 sourceRowsVisible=true autoEnabled=true receiptFailure=false\n"
            "WIMT_SOAK_SOURCE_LOST cycle=35 elapsedMinutes=35\n"
            "WIMT_SOAK_SUMMARY durationMs=1000 cycles=0 requiredReceipts=46 missedReceipts=0 "
            "detailSamples=1 freshSamples=1 nonFreshSamples=0 unqualifiedSamples=0 "
            "httpFailures=0 selectedLosses=0 autoStoppedAfterSourceFailure=false "
            "sourceFailureReason=none fullDuration=false finalSelected=true autoOff=true "
            "currentStationRequired=true currentStationChecksPassed=true\n",
            encoding="utf-8")
        (folder / "instrumentation.txt").write_text(
            "java.lang.AssertionError: strict fresh WIMT .in source response failed\nFAILURES!!!\n",
            encoding="utf-8")
        (folder / "failed-step-log.txt").write_text(
            "java.lang.AssertionError: strict fresh WIMT .in source response failed\n",
            encoding="utf-8")
        (folder / "failed-step-log-status.txt").write_text("available\n", encoding="utf-8")
        observation = {
            "verdict": "NO_VERIFIED_46_MINUTE_WIMT_IN_APP_OBSERVATION",
            "passed": False, "failureStage": "INITIAL_WIMT_LIVE_STATUS_GATE_FAILED",
            "requiredDurationMinutes": 46, "observedDurationMs": None,
            "observedDurationMinutes": None, "trainNo": "16227", "runDate": "12 Oct 2026",
            "route": "MYS → TLGP", "trainSelectionRule": "IST-hour-schedule-candidate",
            "initial": {"accepted": True, "httpStatus": 200, "runDateMatched": True,
                        "routeMatched": True, "freshSourceStamp": True, "sourceRows": 2,
                        "visibleSourceCells": 3, "currentStationCodeClaim": "MYS"},
            "refresh": {"receipts": 0, "requiredReceipts": 46,
                        "missedReceiptSlots": 0, "samples": []},
            "currentStationCheckpoints": [],
            "checks": {"initialFreshRunRouteMatchedWimtInResponse": True,
                       "freshCurrentStationClaimAtStartAndCheckpoints": False},
            "instrumentationPassed": False, "runnerExitCode": 1,
            "screenshots": ["screens/wimt-soak-start.png"],
        }
        summary = summarize_wimt_46_artifact(folder, observation, "44093117512",
                                             "wimt-one-train-46-minute-evidence-44093117512")
        assert summary["sourceRun"] == "44093117512"
        assert summary["trainSelectionRule"] == "IST-hour-schedule-candidate"
        assert summary["currentStationCheckpoints"] == []
        assert summary["refresh"]["receipts"] == 0
        assert summary["runningStatus"] == "NOT_CAPTURED_BY_THIS_RUN_SCHEMA"
        assert summary["initial"]["freshnessAssessment"] == \
            "FRESHNESS_NOT_ESTABLISHED_OR_INCONSISTENT"
        assert summary["runningStatusFailurePoints"] == [{
            "event": "WIMT_SOAK_INITIAL", "cycle": None, "elapsedMinutes": None,
            "httpStatus": 200, "runDateMatched": True, "routeMatched": True,
            "freshSourceStamp": True, "sourceRows": 2,
            "currentStationCodeClaim": "MYS", "appRunningStatusQualified": False,
        }]
        assert summary["sourceLossEvents"] == [{
            "event": "WIMT_SOAK_SOURCE_LOST", "cycle": 35, "elapsedMinutes": 35,
        }]
        assert summary["reportedScreenshotNames"] == ["wimt-soak-start.png"]
        assert summary["screenshotFilesVerified"] is False
        assert summary["traceEvents"][1]["fields"]["parserOutcome"] == "SOURCE_ROWS_PRESENT"
        assert "AssertionError" in summary["failedStepLog"][
            "boundedInstrumentationLines"][0]
        assert "not an explicit provider boolean or movement/GPS proof" in \
            summary["runningStatusBasis"]

    with tempfile.TemporaryDirectory(prefix="wimt-website-recover-") as temporary:
        folder = Path(temporary)
        (folder / "screens").mkdir()
        (folder / "screens" / "wimt-after-selected-source.xml").write_text(
            '<hierarchy><node package="in.opentrack.india" '
            'resource-id="in.opentrack.india:id/trainNo" text="16227" />'
            '<node package="in.opentrack.india" '
            'resource-id="in.opentrack.india:id/routeLabel" text="MYS → TLGP" />'
            '<node package="in.opentrack.india" '
            'resource-id="in.opentrack.india:id/headline" '
            'text="No qualifying past-stop report" /></hierarchy>', encoding="utf-8")
        (folder / "instrumentation.txt").write_text(
            "java.lang.AssertionError: no qualified website past card; "
            "warning='Website refused request (HTTP 403)'\nFAILURES!!!\n",
            encoding="utf-8")
        (folder / "failed-step-log.txt").write_text(
            "java.lang.AssertionError: x-hcdn-request-id=PRIVATE "
            "https://example.invalid/page?token=PRIVATE\n", encoding="utf-8")
        (folder / "failed-step-log-status.txt").write_text("available\n", encoding="utf-8")
        (folder / "screen-verification.json").write_text(json.dumps({
            "verifiedAppScreens": {
                "wimt-before-selected-source": {"pngBytes": 2000, "appNodes": 5},
                "wimt-after-selected-source": {"pngBytes": 2100, "appNodes": 6},
            },
            "displayedRefusalRequired": True,
        }), encoding="utf-8")
        summary = summarize_website_artifact(folder, {
            "source": "whereismytrain.org.in", "accepted": False,
            "wireReceiptAvailable": False, "websiteGetCount": None,
        }, "43260030312", "wimt-device-evidence")
        assert summary["sourceRun"] == "43260030312"
        assert summary["sourceArtifact"] == "wimt-device-evidence"
        assert summary["result"]["verdict"] == "WEBSITE_REFUSED_UI_DIARY_UNAVAILABLE"
        assert summary["capturedInTestHierarchyNames"] == ["wimt-after-selected-source"]
        assert summary["whitelistedUiHierarchySummaries"]["wimt-after-selected-source"][
            "headline"][0]["text"] == "No qualifying past-stop report"
        assert summary["whitelistedUiHierarchySummaries"]["wimt-after-selected-source"][
            "routeLabel"][0]["text"] == "MYS → TLGP"
        assert summary["verifiedInAppScreenshotNames"] == [
            "wimt-after-selected-source", "wimt-before-selected-source"]
        shape_summary = summarize_website_artifact(folder, {
            "source": "whereismytrain.org.in", "accepted": False,
            "wireReceiptAvailable": True, "websiteGetCount": 1,
            "sitePageRunDate": "unknown", "siteDateMatched": False,
            "siteShape": "chars:108395~tags:main:1,article:0~roots:0~cards:0~heads:h1_train_status",
        }, "43260030312", "wimt-device-evidence")
        assert shape_summary["result"]["sitePageRunDate"] == "unknown"
        assert shape_summary["result"]["siteDateMatched"] is False
        assert shape_summary["result"]["siteShape"].endswith("roots:0~cards:0~heads:h1_train_status")
        (folder / "screen-verification.json").unlink()
        (folder / "screen-verification-diagnostic.json").write_text(json.dumps({
            "verifiedAppScreens": {"wimt-after-selected-source": {"pngBytes": 2100}},
            "verificationMode": "DIAGNOSTIC", "selectedWebsiteObserved": True,
        }), encoding="utf-8")
        diagnostic_summary = summarize_website_artifact(folder, {
            "source": "whereismytrain.org.in", "accepted": False,
            "wireReceiptAvailable": True, "websiteGetCount": 1,
        }, "43260030312", "wimt-device-evidence")
        assert diagnostic_summary["screenVerificationFilePresent"] is False
        assert diagnostic_summary["diagnosticScreenVerificationFilePresent"] is True
        assert diagnostic_summary["verifiedInAppScreenshotNames"] == []
        assert diagnostic_summary["diagnosticInAppScreenshotNames"] == [
            "wimt-after-selected-source"]
        assert diagnostic_summary["screenVerificationMode"] == "DIAGNOSTIC"
        assert "PRIVATE" not in json.dumps(summary)
        assert "<url>" in json.dumps(summary)
    with tempfile.TemporaryDirectory(prefix="wimt-direct-source-recover-") as temporary:
        folder = Path(temporary)
        (folder / "report.md").write_text(
            "# 45-minute direct WIMT `.in` source observation\n\n"
            "No APK or emulator was used.\n", encoding="utf-8")
        direct_result = {
            "mode": "direct-source-only",
            "source": "whereismytrain.in/cache/live_status",
            "passed": False,
            "verdict": "NO_VERIFIED_45_MINUTE_DIRECT_WIMT_IN_OBSERVATION",
            "trainNo": "12627", "runDate": "12-10-2026", "route": "MYS->TLGP",
            "selectionStopReason": "observation_gate_failed",
            "observation": {
                "durationMinutes": 35.1, "requiredDurationMinutes": 45,
                "recordedCheckpointMinutes": [0, 5, 10, 15, 20, 25, 30, 35],
                "checks": {"elapsedAtLeast45Minutes": False},
                "samples": [{
                    "checkpointMinute": 35, "httpStatus": 200,
                    "freshSourceStamp": False, "qualified": False,
                    "responseSha256": "a" * 64,
                    "rawResponse": "PRIVATE raw body must never be copied",
                }],
            },
            "candidateAttempts": [],
            "reportMarkdown": "# Direct-source report; no app test.\n",
        }
        direct_summary = summarize_direct_source_artifact(
            folder, direct_result, "123456", "wimt-direct-source-45-minute-evidence-123456")
        assert direct_summary["mode"] == "direct-source-only"
        assert direct_summary["durationMinutes"] == 35.1
        assert direct_summary["samples"][0]["checkpointMinute"] == 35
        assert "rawResponse" not in json.dumps(direct_summary)
        assert direct_summary["noProviderRequestDuringRecovery"] is True
    print("WIMT artifact recovery self-test passed (no network or provider request)")


def _run_live_report(folder: Path, original: dict) -> int:
    source_run = os.environ.get("WIMT_SOURCE_RUN", "unknown")
    source_artifact = os.environ.get("WIMT_SOURCE_ARTIFACT", "unknown")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,100}", source_artifact):
        source_artifact = "invalid-artifact-name"
    summary = summarize_live_artifact(folder, original, source_run, source_artifact)
    report = [
        "### Existing installed WIMT `.in` artifact · recovery diagnosis (no new provider request)",
        "", "```json", json.dumps(summary, indent=2, sort_keys=True), "```", "",
        "This is a bounded file/recovery diagnosis, not source-parity acceptance. Diagnostic captures "
        "never satisfy the three-comparison gate.",
    ]
    text = "\n".join(report) + "\n"
    (folder / "recovered-report.md").write_text(text, encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        _self_test()
        raise SystemExit(0)
    if len(sys.argv) != 2:
        raise SystemExit("usage: wimt_recover.py ARTIFACT_DIR | --self-test")
    folder = Path(sys.argv[1])
    original = _read_json(folder / "result.json")
    if isinstance(original, dict) and original.get("mode") == "direct-source-only":
        raise SystemExit(_run_direct_source_report(folder, original))
    if isinstance(original, dict) and _is_wimt_46_result(original):
        raise SystemExit(_run_wimt_46_report(folder, original))
    if isinstance(original, dict) and _is_live_comparison_result(original):
        raise SystemExit(_run_live_report(folder, original))

    original = original if isinstance(original, dict) else {}
    if original.get("source") == "whereismytrain.org.in" or \
            os.environ.get("WIMT_SOURCE_ARTIFACT") == "wimt-device-evidence":
        raise SystemExit(_run_website_report(folder, original))
    inst = (folder / "instrumentation.txt").read_text(errors="replace") \
        if (folder / "instrumentation.txt").exists() else ""
    boot = (folder / "boot.txt").read_text(errors="replace") \
        if (folder / "boot.txt").exists() else ""
    from wimt_device_report import verdict
    # Reinterpret only the existing visible test failure if the diary was absent.
    # No added GET, no invented wire count or source success.
    result = (verdict(inst, "", 1) if not original.get("accepted") and
              (not original.get("wireReceiptAvailable", False) or
               original.get("websiteGetCount") == 0) and inst else original)
    report = ["### Existing WIMT emulator run · failure diagnosis (no new site GET)", "",
              "```json", json.dumps(result, sort_keys=True), "```", "",
              "Instrumented test assertions/results (bounded, no raw diary):", "```"]
    report += brief(inst) or ["No instrumented result captured; boot tail follows."]
    if not inst:
        report += [re.sub(r"https?://\S+", "<url>", s)[:220] for s in boot.splitlines()[-12:]]
    verified = (folder / "screen-verification.json").exists()
    report += ["```", "", "Verified in-app screenshots remain in the originating run's artifact."
               if verified else "In-app screenshots were NOT verified; do not present the artifact as app evidence."]
    (folder / "recovered-report.md").write_text("\n".join(report) + "\n")
    print("\n".join(report))
