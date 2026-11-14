#!/usr/bin/env python3
"""Aggregate the focused installed-APK WIMT multi-train comparison evidence.

Only bounded parsed results and verified in-app captures are included. No response body, request
URL, cookies, headers, or browser identity is read or emitted. A successful comparison is not
reported as source parity if station-event fields disagree.
"""
from pathlib import Path
import argparse
import json
import re
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

from verify_wimt_live_comparison_screens import parse_attempts

PASS_LINE = "WIMT_MULTI_VERDICT=PASS_THREE_FRESH_SAME_TRAIN_DATE_COMPARISONS_NOT_PARITY"
MIN_COMPARISONS = 3
SCREENSHOT_SUFFIXES = {
    "appLive": "app-live",
    "appDetails": "app-details",
    "webCache": "web-cache",
    "webDetails": "web-details",
}


def _valid_capture_manifest(row: dict) -> bool:
    train = row.get("trainNo")
    captures = row.get("screenshots")
    if not isinstance(train, str) or not re.fullmatch(r"[0-9]{4,5}", train) or \
            not isinstance(captures, list) or len(captures) != len(SCREENSHOT_SUFFIXES):
        return False
    by_role = {capture.get("role"): capture for capture in captures
               if isinstance(capture, dict)}
    if set(by_role) != set(SCREENSHOT_SUFFIXES):
        return False
    for role, suffix in SCREENSHOT_SUFFIXES.items():
        capture = by_role[role]
        dimensions = capture.get("dimensions")
        if capture.get("name") != f"wimt-compare-{train}-{suffix}.png" or \
                not isinstance(capture.get("bytes"), int) or capture["bytes"] <= 1_000 or \
                not isinstance(dimensions, list) or len(dimensions) != 2 or \
                not all(isinstance(value, int) and value > 0 for value in dimensions):
            return False
    return True


def _parse_ist_millis(value: str) -> int | None:
    if not isinstance(value, str):
        return None
    for pattern in ("%d %b %Y %H:%M:%S.%f IST", "%d %b %Y %H:%M:%S IST"):
        try:
            parsed = datetime.strptime(value, pattern).replace(tzinfo=ZoneInfo("Asia/Kolkata"))
            return int(parsed.timestamp() * 1_000)
        except ValueError:
            continue
    return None


def _web_freshness_consistent(attempt: dict) -> bool:
    updated = _parse_ist_millis(attempt.get("webFreshestRowUpdatedAt"))
    checked = _parse_ist_millis(attempt.get("webFreshnessCheckedAt"))
    age_seconds = attempt.get("webFreshestRowAgeSeconds")
    if updated is None or checked is None or not isinstance(age_seconds, int) or \
            isinstance(age_seconds, bool):
        return False
    age_millis = checked - updated
    return 0 <= age_millis <= 600_000 and age_millis // 1_000 == age_seconds


def _strict_attempt(attempt: dict) -> bool:
    try:
        return (
            attempt.get("verdict") == "COMPARISON_COMPLETE" and
            isinstance(attempt.get("trainNo"), str) and
            attempt.get("appTrainNumberMatched") is True and
            attempt.get("webRequestTrainNumber") == attempt.get("trainNo") and
            attempt.get("webRequestTrainNumberMatchesApp") is True and
            bool(attempt.get("serviceDate")) and
            attempt.get("serviceDate") == attempt.get("webServiceDate") and
            attempt.get("directSelected") is True and
            attempt.get("directHttpStatus") == 200 and
            attempt.get("directFresh") is True and
            attempt.get("directDateMatched") is True and
            attempt.get("directRouteMatched") is True and
            attempt.get("directCurrentStationClaim") is True and
            isinstance(attempt.get("directSourceRows"), int) and
            attempt["directSourceRows"] > 0 and
            isinstance(attempt.get("directVisibleTimeCells"), int) and
            attempt["directVisibleTimeCells"] > 0 and
            attempt.get("webSelected") is True and
            attempt.get("webHttpStatus") == 200 and
            isinstance(attempt.get("webSourceRows"), int) and
            attempt["webSourceRows"] > 0 and
            attempt.get("webFresh") is True and
            isinstance(attempt.get("webFreshestRowAgeSeconds"), int) and
            not isinstance(attempt.get("webFreshestRowAgeSeconds"), bool) and
            0 <= attempt["webFreshestRowAgeSeconds"] <= 600 and
            _web_freshness_consistent(attempt) and
            attempt.get("webDateMatched") is True and
            attempt.get("webRouteMatched") is True and
            attempt.get("webTrainNumberEchoMatched") is not False and
            attempt.get("defaultPreferenceRestored") is True and
            attempt.get("defaultResetSucceeded") is True and
            attempt.get("stopAfterHostFailure") is False and
            isinstance(attempt.get("overlappingEventFields"), int) and
            attempt["overlappingEventFields"] > 0
        )
    except (KeyError, TypeError):
        return False


def make_report(instrumentation: str, device_log: str, return_code: int,
                screen_verification: str = "") -> dict:
    attempts = parse_attempts(device_log)
    complete_logged = [a for a in attempts if a.get("verdict") == "COMPARISON_COMPLETE"]
    strict = [a for a in complete_logged if _strict_attempt(a)]
    try:
        screens = json.loads(screen_verification)
    except (json.JSONDecodeError, TypeError):
        screens = {}
    if not isinstance(screens, dict):
        screens = {}
    screen_rows = screens.get("comparisons", [])
    if not isinstance(screen_rows, list):
        screen_rows = []
    screen_by_train_date = {
        (row.get("trainNo"), row.get("serviceDate")): row
        for row in screen_rows if isinstance(row, dict)
    }
    verified = [a for a in strict if
                (a.get("trainNo"), a.get("serviceDate")) in screen_by_train_date]
    test_pass = return_code == 0 and bool(re.search(r"\bOK \(1 test\)", instrumentation)) and \
        "FAILURES!!!" not in instrumentation and "INSTRUMENTATION_FAILED" not in instrumentation and \
        PASS_LINE in device_log
    capture_manifests_ok = bool(screen_rows) and all(
        isinstance(row, dict) and _valid_capture_manifest(row) for row in screen_rows
    )
    screenshot_verifier_ok = screens.get("verified") is True and \
        screens.get("appPackage") == "in.opentrack.india" and \
        screens.get("captureSource") == \
            "fresh in-test UiAutomator screenshots and matching app-window hierarchies" and \
        capture_manifests_ok and \
        screens.get("acceptedComparisonCount", len(screen_rows)) == len(screen_rows)
    logged_keys = [(a.get("trainNo"), a.get("serviceDate")) for a in complete_logged]
    screen_keys = [(row.get("trainNo"), row.get("serviceDate"))
                   for row in screen_rows if isinstance(row, dict)]
    in_app_screens_verified = screenshot_verifier_ok and bool(complete_logged) and \
        len(screen_keys) == len(complete_logged) and \
        len(set(screen_keys)) == len(screen_keys) and set(screen_keys) == set(logged_keys)
    accepted = test_pass and in_app_screens_verified and len(verified) >= MIN_COMPARISONS

    comparisons = []
    conflicts = []
    for attempt in verified:
        screen = screen_by_train_date[(attempt.get("trainNo"), attempt.get("serviceDate"))]
        for key in ("overlappingEventFields", "matchedEventFields", "mismatchFields",
                    "appOnlyEventFields", "webOnlyEventFields"):
            if attempt.get(key) != screen.get(key):
                conflicts.append(f"{attempt.get('trainNo')}/{attempt.get('serviceDate')}:{key}")
        comparisons.append({
            "trainNo": attempt.get("trainNo"),
            "serviceDate": attempt.get("serviceDate"),
            "trainNumberMatch": {
                "appScreenMatchesRequestedTrain": attempt.get("appTrainNumberMatched"),
                "webRequestTrainNumber": attempt.get("webRequestTrainNumber"),
                "webResponseEchoMatched": attempt.get("webTrainNumberEchoMatched"),
            },
            "directSource": {
                "status": f"HTTP {attempt.get('directHttpStatus')}",
                "freshSourceUpdate": attempt.get("directFresh"),
                "responseRunDateMatched": attempt.get("directDateMatched"),
                "bothRouteEndpointsMatched": attempt.get("directRouteMatched"),
                "currentStationClaimVisible": attempt.get("directCurrentStationClaim"),
                "sourceReportedRows": attempt.get("directSourceRows"),
                "visibleSourceAttributedCells": attempt.get("directVisibleTimeCells"),
            },
            "webCacheComparison": {
                "status": f"HTTP {attempt.get('webHttpStatus')}",
                "responseTrainNumberEchoMatched": attempt.get("webTrainNumberEchoMatched"),
                "rowUpdateFresh": attempt.get("webFresh"),
                "freshestRowAgeSeconds": attempt.get("webFreshestRowAgeSeconds"),
                "freshestRowUpdatedAt": attempt.get("webFreshestRowUpdatedAt"),
                "freshnessCheckedAt": attempt.get("webFreshnessCheckedAt"),
                "serviceDateMatched": attempt.get("webDateMatched"),
                "routeMatched": attempt.get("webRouteMatched"),
                "sourceReportedRows": attempt.get("webSourceRows"),
            },
            "eventComparison": {
                "overlappingFields": attempt.get("overlappingEventFields"),
                "matchedFields": attempt.get("matchedEventFields"),
                "mismatches": attempt.get("mismatchFields", []),
                "onlyInSelectedAppSource": attempt.get("appOnlyEventFields", []),
                "onlyInWebCacheComparison": attempt.get("webOnlyEventFields", []),
                "eventValuesMatch": not any((attempt.get("mismatchFields"),
                                               attempt.get("appOnlyEventFields"),
                                               attempt.get("webOnlyEventFields"))),
                "independentSourceParityVerified": False,
            },
            "screenshots": screen.get("screenshots", []),
        })

    accepted = accepted and not conflicts
    mismatch_count = sum(len(c["eventComparison"]["mismatches"]) +
                         len(c["eventComparison"]["onlyInSelectedAppSource"]) +
                         len(c["eventComparison"]["onlyInWebCacheComparison"])
                         for c in comparisons)
    all_refused = any(a.get("verdict", "").endswith(("HTTP_403_REFUSED", "HTTP_429_RATE_LIMITED"))
                      for a in attempts)
    attempt_verdict_counts: dict[str, int] = {}
    for attempt in attempts:
        label = str(attempt.get("verdict", "UNKNOWN"))
        attempt_verdict_counts[label] = attempt_verdict_counts.get(label, 0) + 1
    if accepted:
        verdict = "COMPARISONS_COMPLETE_MISMATCHES_REPORTED" if mismatch_count else \
            "COMPARISONS_COMPLETE_NO_EVENT_MISMATCH"
        reason = (f"{len(verified)} same-train/date comparisons passed the installed-app freshness, "
                  "identity, current-station, and in-app screenshot gates. This is a comparison run, "
                  "not independent WIMT phone-app verification.")
    elif all_refused:
        verdict = "LIVE_SOURCE_REFUSED_OR_RATE_LIMITED"
        reason = "The `.in` source refused or rate-limited a request; no retry was made and no live parity claim is made."
    elif conflicts:
        verdict = "CAPTURE_OR_RESULT_CONFLICT"
        reason = "The installed-test result and captured app hierarchy disagree; the comparison is not accepted."
    elif complete_logged and not screenshot_verifier_ok:
        verdict = "GENUINE_COMPARISON_SCREENSHOTS_NOT_VERIFIED"
        reason = "At least one source comparison completed, but matching, validated installed-app screenshots were not provided."
    else:
        verdict = "INSUFFICIENT_FRESH_SAME_DATE_COMPARISONS"
        if not complete_logged:
            reasons = ", ".join(f"{name}={count}" for name, count in sorted(attempt_verdict_counts.items()))
            reason = (f"No attempt completed the fresh same-train/date event comparison (0 of "
                      f"{MIN_COMPARISONS} required). Attempt verdicts: {reasons or 'none'}. "
                      "Acceptance screenshot verification was not applicable because there were no "
                      "completed comparisons; diagnostic capture checks do not satisfy that gate.")
        else:
            reason = (f"Only {len(verified)} of {MIN_COMPARISONS} required comparisons met every source, "
                      "same-date, freshness, event-row, and genuine screenshot gate.")

    return {
        "source": "Selected WIMT `.in` live-status source vs the explicit WIMT web-cache comparison path; both request whereismytrain.in/cache/live_status.",
        "notTheSource": ["whereismytrain.org.in website", "Google Where Is My Train Android app"],
        "comparisonMeaning": "Two TrackTrain source paths to the same undocumented web-cache endpoint; not independent-source parity.",
        "accepted": accepted,
        "verdict": verdict,
        "parityVerified": False,
        "eventValuesMatch": accepted and mismatch_count == 0,
        "minimumComparisons": MIN_COMPARISONS,
        "completedComparisons": len(verified),
        "eventMismatchCount": mismatch_count,
        "sourceAttemptVerdictCounts": attempt_verdict_counts,
        "installedApkTestPassed": test_pass,
        "deviceTestResultCaptured": bool(attempts),
        "inAppScreensVerified": in_app_screens_verified,
        "screenshotVerification": screens,
        "evidenceConflicts": sorted(set(conflicts)),
        "comparisons": comparisons,
        "attempts": attempts,
        "reason": reason,
        "returnCode": return_code,
        "pixelTextVerified": False,
    }


def _valid_attempt(train: str) -> dict:
    return {
        "trainNo": train, "serviceDate": "12 Oct 2026", "webServiceDate": "12 Oct 2026",
        "appTrainNumberMatched": True, "webRequestTrainNumber": train,
        "webRequestTrainNumberMatchesApp": True,
        "verdict": "COMPARISON_COMPLETE", "directSelected": True, "directHttpStatus": 200,
        "directFresh": True, "directDateMatched": True, "directRouteMatched": True,
        "directCurrentStationClaim": True, "directSourceRows": 4, "directVisibleTimeCells": 3,
        "webSelected": True, "webHttpStatus": 200, "webSourceRows": 4, "webFresh": True,
        "webDateMatched": True, "webRouteMatched": True, "webTrainNumberEchoMatched": None,
        "webFreshestRowAgeSeconds": 40,
        "webFreshestRowUpdatedAt": "12 Oct 2026 10:21:00 IST",
        "webFreshnessCheckedAt": "12 Oct 2026 10:21:40.000 IST",
        "defaultActiveSourceRestored": True, "defaultPreferenceRestored": True,
        "defaultResetSucceeded": True, "stopAfterHostFailure": False,
        "overlappingEventFields": 2, "matchedEventFields": 1,
        "mismatchFields": ["SBC arrival app=10:19 web=10:20"],
        "appOnlyEventFields": [], "webOnlyEventFields": [],
    }


def self_test() -> None:
    attempts = [_valid_attempt(n) for n in ("16315", "12951", "12301")]
    log = "\n".join("I WimtMultiTrain: WIMT_MULTI_ATTEMPT_JSON=" +
                     json.dumps(a) for a in attempts)
    log += "\nI WimtMultiTrain: " + PASS_LINE
    screen_rows = [{
        "trainNo": a["trainNo"], "serviceDate": a["serviceDate"],
        "overlappingEventFields": a["overlappingEventFields"],
        "matchedEventFields": a["matchedEventFields"],
        "mismatchFields": a["mismatchFields"], "appOnlyEventFields": [],
        "webOnlyEventFields": [],
        "screenshots": [
            {"role": role, "name": f"wimt-compare-{a['trainNo']}-{suffix}.png",
             "bytes": 24_000, "dimensions": [1080, 2400]}
            for role, suffix in SCREENSHOT_SUFFIXES.items()
        ],
    } for a in attempts]
    screens = json.dumps({"verified": True, "appPackage": "in.opentrack.india",
        "captureSource": "fresh in-test UiAutomator screenshots and matching app-window hierarchies",
        "acceptedComparisonCount": len(screen_rows), "comparisons": screen_rows})
    report = make_report("OK (1 test)", log, 0, screens)
    assert report["accepted"] is True
    assert report["parityVerified"] is False
    assert report["eventMismatchCount"] == 3
    stale = json.loads(json.dumps(attempts))
    stale[0]["directFresh"] = False
    stale_log = "\n".join("WIMT_MULTI_ATTEMPT_JSON=" + json.dumps(a) for a in stale)
    stale_log += "\n" + PASS_LINE
    assert make_report("OK (1 test)", stale_log, 0, screens)["accepted"] is False
    no_screens = make_report("OK (1 test)", log, 0, "{}")
    assert no_screens["accepted"] is False
    assert no_screens["verdict"] == "GENUINE_COMPARISON_SCREENSHOTS_NOT_VERIFIED"
    stale_cache = _valid_attempt("16315")
    stale_cache.update(verdict="WEB_CACHE_SOURCE_NOT_QUALIFIED", webFresh=False)
    stale_log = "WIMT_MULTI_ATTEMPT_JSON=" + json.dumps(stale_cache)
    incomplete = make_report("FAILURES!!!", stale_log, 1, "{}")
    assert incomplete["accepted"] is False
    assert incomplete["verdict"] == "INSUFFICIENT_FRESH_SAME_DATE_COMPARISONS"
    assert incomplete["inAppScreensVerified"] is False
    refused = _valid_attempt("16315")
    refused.update(verdict="DIRECT_HTTP_403_REFUSED", directHttpStatus=403)
    refused_log = "WIMT_MULTI_ATTEMPT_JSON=" + json.dumps(refused)
    assert make_report("FAILURES!!!", refused_log, 1, "{}")["verdict"] == \
        "LIVE_SOURCE_REFUSED_OR_RATE_LIMITED"
    print("WIMT live comparison report self-test passed: freshness, screenshots, refusals, and mismatches stay explicit")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instrumentation", type=Path)
    parser.add_argument("--device-log", type=Path)
    parser.add_argument("--return-code", type=int, default=1)
    parser.add_argument("--screen-verification", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.instrumentation is None or args.device_log is None:
        parser.error("instrumentation and --device-log are required")
    screen_text = ""
    if args.screen_verification and args.screen_verification.is_file():
        screen_text = args.screen_verification.read_text(encoding="utf-8", errors="replace")
    try:
        report = make_report(
            args.instrumentation.read_text(encoding="utf-8", errors="replace"),
            args.device_log.read_text(encoding="utf-8", errors="replace"),
            args.return_code,
            screen_text,
        )
    except (OSError, ValueError) as exc:
        print(f"WIMT_LIVE_COMPARISON_REPORT_NOT_CREATED: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report.get("accepted") else 2


if __name__ == "__main__":
    raise SystemExit(main())
