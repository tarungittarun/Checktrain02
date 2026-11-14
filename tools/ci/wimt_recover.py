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
        "siteRoute", "selectedRoute", "liveIdentityRouteMatch", "siteClaimStation",
        "siteClaimKind", "siteClaimMinuteFromOrigin", "siteClaimTimeBasis", "siteClaimTimeIst",
        "siteClaimAgeSecondsAtCheck", "pagePublishedAtIst", "pageAgeSecondsAtCheck",
        "checkedAtIst", "provenanceComplete", "googlePhoneAppFeed", "browserEngineRequest",
        "websiteGetCountScope", "note",
    )
    safe_result = {key: result[key] for key in safe_fields if key in result}
    screens = _read_json(folder / "screen-verification.json") or {}
    verified = screens.get("verifiedAppScreens", {})
    if not isinstance(verified, dict):
        verified = {}
    failed_log = _read_text(folder / "failed-step-log.txt")
    hierarchy_names = ("wimt-before-selected-source", "wimt-after-selected-source")
    captured_hierarchies = {}
    for name in hierarchy_names:
        path = folder / "screens" / f"{name}.xml"
        if path.is_file():
            captured_hierarchies[name] = _ui_hierarchy(path)
    return {
        "sourceRun": source_run,
        "sourceArtifact": source_artifact,
        "result": safe_result,
        "screenVerificationFilePresent": bool(screens),
        "verifiedInAppScreenshotNames": sorted(verified),
        "capturedInTestHierarchyNames": sorted(captured_hierarchies),
        "whitelistedUiHierarchySummaries": captured_hierarchies,
        "displayedRefusalRequired": screens.get("displayedRefusalRequired"),
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
        assert summary["result"]["verdict"] == "WEBSITE_REFUSED_UI_DIARY_UNAVAILABLE"
        assert summary["capturedInTestHierarchyNames"] == ["wimt-after-selected-source"]
        assert summary["whitelistedUiHierarchySummaries"]["wimt-after-selected-source"][
            "headline"][0]["text"] == "No qualifying past-stop report"
        assert summary["whitelistedUiHierarchySummaries"]["wimt-after-selected-source"][
            "routeLabel"][0]["text"] == "MYS → TLGP"
        assert summary["verifiedInAppScreenshotNames"] == [
            "wimt-after-selected-source", "wimt-before-selected-source"]
        assert "PRIVATE" not in json.dumps(summary)
        assert "<url>" in json.dumps(summary)
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
