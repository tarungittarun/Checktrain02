#!/usr/bin/env python3
"""Bounded diagnosis of an existing WIMT device artifact; never makes a provider request."""
from collections import Counter
from pathlib import Path
import json
import os
import re
import sys
import tempfile

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
                "INSTRUMENTATION_RESULT:", "Tests run:", "Process crashed", "OK (", "AssertionError:")
    for line in log.splitlines():
        if any(k in line for k in patterns):
            # This test's assertions include public UI labels only; still do not
            # print arbitrary stack frames, URLs, session headers or CDN IDs.
            clean = re.sub(r"(?i)(x-hcdn-request-id|cdnRequestId|cookie|token)[=: ]+\S+",
                           r"\1=<redacted>", line)[:280]
            lines.append(clean)
    return lines[-18:]


def _read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


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
