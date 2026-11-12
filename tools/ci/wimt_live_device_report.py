#!/usr/bin/env python3
"""Bounded verdict for the installed-app WIMT `.in` live-status test.

Never reads or prints response JSON, request headers, cookies, device diary, or private IDs.
"""
from pathlib import Path
import json
import re
import sys

PASS_LINE = "WIMT_LIVE_DEVICE_VERDICT=PASS_FRESH_MATCHED_SOURCE_TIMES_IN_APP"
RESULT_RE = re.compile(
    r"WIMT_LIVE_DEVICE_RESULT http200=(true|false) runDateMatched=(true|false) "
    r"routeMatched=(true|false) freshUpdate=(true|false) evidenceRows=(\d+) "
    r"visibleSourceCells=(\d+) selected=(true|false)", re.IGNORECASE)


def make_report(instrumentation: str, return_code: int,
                screen_verification: str = "") -> dict:
    exactly_one = bool(re.search(r"\bOK \(1 test\)", instrumentation)) and \
        "FAILURES!!!" not in instrumentation and "INSTRUMENTATION_FAILED" not in instrumentation
    match = RESULT_RE.search(instrumentation)
    parsed = {
        "http200": match.group(1).lower() == "true" if match else None,
        "runDateMatched": match.group(2).lower() == "true" if match else None,
        "routeEndpointsMatched": match.group(3).lower() == "true" if match else None,
        "freshSourceStamp": match.group(4).lower() == "true" if match else None,
        "sourceRowsInDetails": int(match.group(5)) if match else None,
        "wimtAttributedVisibleTimeCells": int(match.group(6)) if match else None,
        "selectedSourceVisible": match.group(7).lower() == "true" if match else None,
    }
    try:
        screen = json.loads(screen_verification)
    except (json.JSONDecodeError, TypeError):
        screen = {}
    if not isinstance(screen, dict):
        screen = {}
    required_screens = {
        "wimt-live-before-selected-source.png",
        "wimt-live-after-selected-source.png",
        "wimt-live-source-details.png",
    }
    items = screen.get("screenshots", []) if isinstance(screen, dict) else []
    names = {item.get("name") for item in items if isinstance(item, dict)} \
        if isinstance(items, list) else set()
    screens_ok = isinstance(screen, dict) and screen.get("verified") is True and \
        screen.get("appPackage") == "in.opentrack.india" and \
        screen.get("captureSource") == \
            "in-test UiAutomator screenshot plus matching app-window hierarchy" and \
        required_screens.issubset(names)
    status = screen.get("httpStatusVisibleInDetails") if isinstance(screen, dict) else None
    rows = parsed["sourceRowsInDetails"]
    if rows is None and isinstance(screen.get("sourceRowsInDetails"), int):
        rows = screen["sourceRowsInDetails"]
    cells = parsed["wimtAttributedVisibleTimeCells"]
    if cells is None and isinstance(screen.get("wimtAttributedVisibleTimeCells"), int):
        cells = screen["wimtAttributedVisibleTimeCells"]

    # Preserve facts visible in the verified in-app hierarchy when the instrumented
    # result line was not captured. This can describe evidence, never create a pass:
    # accepted still requires the successful one-test result and explicit verdict log.
    result_captured = match is not None
    if screens_ok:
        if parsed["http200"] is None and status is not None:
            parsed["http200"] = status == "HTTP 200"
        if parsed["runDateMatched"] is None:
            value = screen.get("runDateMatchedVisible")
            parsed["runDateMatched"] = value if isinstance(value, bool) else None
        if parsed["routeEndpointsMatched"] is None:
            value = screen.get("routeEndpointsMatchedVisible")
            parsed["routeEndpointsMatched"] = value if isinstance(value, bool) else None
        if parsed["freshSourceStamp"] is None:
            value = screen.get("freshSourceStampVisible")
            parsed["freshSourceStamp"] = value if isinstance(value, bool) else None
        if parsed["sourceRowsInDetails"] is None:
            parsed["sourceRowsInDetails"] = rows
        if parsed["wimtAttributedVisibleTimeCells"] is None:
            parsed["wimtAttributedVisibleTimeCells"] = cells
        if parsed["selectedSourceVisible"] is None:
            parsed["selectedSourceVisible"] = True

    test_candidate = return_code == 0 and exactly_one and PASS_LINE in instrumentation and match is not None
    strict_data = parsed["http200"] is True and parsed["runDateMatched"] is True and \
        parsed["routeEndpointsMatched"] is True and parsed["freshSourceStamp"] is True and \
        parsed["selectedSourceVisible"] is True and rows is not None and rows > 0 and \
        cells is not None and cells > 0
    accepted = test_candidate and strict_data and screens_ok and status == "HTTP 200" and \
        screen.get("acceptedDataVerifiedInAppHierarchy") is True

    text = instrumentation.lower()
    visible_matched_rows = screens_ok and status == "HTTP 200" and rows is not None and rows > 0 and \
        cells is not None and cells > 0 and screen.get("runDateMatchedVisible") is True and \
        screen.get("routeEndpointsMatchedVisible") is True
    if accepted:
        verdict = "PASS_FRESH_MATCHED_SOURCE_TIMES_IN_APP"
        reason = "The installed APK fetched fresh WIMT `.in` data for the selected run and route and showed source-attributed stop clocks in the app."
    elif status == "HTTP 403" or "http 403" in text:
        verdict = "HTTP_403_REFUSED"
        reason = "The selected `.in` request was refused; no WIMT data acceptance is claimed."
    elif status == "HTTP 429" or "http 429" in text:
        verdict = "HTTP_429_RATE_LIMITED"
        reason = "The selected `.in` request was rate-limited; no retry or WIMT data acceptance is claimed."
    elif (status is not None and re.fullmatch(r"HTTP 5[0-9]{2}", status)) or \
            re.search(r"\bhttp 5[0-9]{2}\b", text):
        verdict = "HTTP_5XX_SERVER_ERROR"
        reason = "The selected `.in` server returned a 5xx response; no WIMT data acceptance is claimed."
    elif visible_matched_rows and parsed["freshSourceStamp"] is not True:
        verdict = "HTTP_200_MATCHED_SOURCE_ROWS_BUT_FRESHNESS_UNPROVEN"
        reason = "Verified in-app captures show run/route-matched source rows, but the source update stamp did not pass the freshness gate."
    elif visible_matched_rows and not test_candidate:
        verdict = "MATCHED_SOURCE_ROWS_VISIBLE_DEVICE_ASSERTION_FAILED"
        reason = "Verified in-app captures show run/route-matched source rows, but the installed-app assertion did not produce a passing one-test verdict; acceptance remains failed."
    elif (status == "HTTP 200" or parsed["http200"] is True or
          re.search(r"\bHTTP[/: ]+200\b", instrumentation, re.IGNORECASE)) and \
            (rows == 0 or not strict_data):
        verdict = "HTTP_200_NO_QUALIFYING_LIVE_DATA"
        reason = "An HTTP answer did not produce fresh, run/route-matched source stop clocks displayed in the app."
    elif "sockettimeoutexception" in text or "connectexception" in text or "timed out" in text:
        verdict = "TRANSPORT_FAILED"
        reason = "The installed-app source request did not complete; no WIMT data acceptance is claimed."
    elif return_code != 0 or not exactly_one:
        verdict = "DEVICE_TEST_FAILED_OR_NOT_EXECUTED"
        reason = "The strict installed-app assertion did not pass; inspect its retained test output and screenshots."
    else:
        verdict = "STRICT_LIVE_ACCEPTANCE_NOT_PROVEN"
        reason = "A green runner summary alone is insufficient; live response, identity, freshness, displayed clocks, or verified screenshots are missing."
    return {
        "source": "whereismytrain.in/cache/live_status (selected WIMT source; endpoint may be server-cached)",
        "notTheSource": ["whereismytrain.org.in website", "app's comparison-only WIMT web-cache path", "Google Where Is My Train Android app"],
        "accepted": accepted,
        "verdict": verdict,
        "installedApkTestPassed": return_code == 0 and exactly_one,
        "deviceTestResultCaptured": result_captured,
        "liveEvidence": parsed,
        "httpStatusVisibleInSourceDetails": status,
        "inAppScreensVerified": screens_ok,
        "acceptedDataVerifiedInAppHierarchy": screens_ok and
            screen.get("acceptedDataVerifiedInAppHierarchy") is True,
        "pixelTextVerified": False,
        "reason": reason,
        "returnCode": return_code,
    }


def self_test() -> None:
    screens = {
        "verified": True,
        "appPackage": "in.opentrack.india",
        "captureSource": "in-test UiAutomator screenshot plus matching app-window hierarchy",
        "acceptedDataVerifiedInAppHierarchy": True,
        "sourceRowsInDetails": 3,
        "sourceRowsInSummary": 3,
        "wimtAttributedVisibleTimeCells": 2,
        "httpStatusVisibleInDetails": "HTTP 200",
        "runDateMatchedVisible": True,
        "routeEndpointsMatchedVisible": True,
        "freshSourceStampVisible": True,
        "screenshots": [
            {"name": "wimt-live-before-selected-source.png"},
            {"name": "wimt-live-after-selected-source.png"},
            {"name": "wimt-live-source-details.png"},
        ],
    }
    line = ("OK (1 test)\nWIMT_LIVE_DEVICE_RESULT http200=true runDateMatched=true "
            "routeMatched=true freshUpdate=true evidenceRows=3 visibleSourceCells=2 selected=true\n" + PASS_LINE)
    assert make_report(line, 0, json.dumps(screens))["accepted"] is True
    screenshot_only = make_report("FAILURES!!!\nHTTP 200", 1, json.dumps(screens))
    assert screenshot_only["accepted"] is False
    assert screenshot_only["verdict"] == "MATCHED_SOURCE_ROWS_VISIBLE_DEVICE_ASSERTION_FAILED"
    assert screenshot_only["liveEvidence"]["sourceRowsInDetails"] == 3
    assert screenshot_only["liveEvidence"]["wimtAttributedVisibleTimeCells"] == 2
    assert screenshot_only["deviceTestResultCaptured"] is False
    for output, expected in [
        ("HTTP 403", "HTTP_403_REFUSED"),
        ("HTTP 429", "HTTP_429_RATE_LIMITED"),
        ("HTTP 500", "HTTP_5XX_SERVER_ERROR"),
        ("HTTP 200", "HTTP_200_NO_QUALIFYING_LIVE_DATA"),
        ("SocketTimeoutException", "TRANSPORT_FAILED"),
    ]:
        report = make_report("FAILURES!!!\n" + output, 1)
        assert report["accepted"] is False and report["verdict"] == expected
    bad = line.replace("freshUpdate=true", "freshUpdate=false")
    assert not make_report(bad, 0, json.dumps(screens))["accepted"]
    wrong_source = json.dumps({**screens, "appPackage": "launcher"})
    assert not make_report(line, 0, wrong_source)["accepted"]
    assert not make_report(line, 0, "[]")["accepted"]
    assert not make_report("OK (1 test)", 0, json.dumps(screens))["accepted"]
    print("WIMT live device report self-test passed: refusal, no data, stale, wrong app, and missing evidence stay red")


def main() -> int:
    if sys.argv[1:] == ["--self-test"]:
        self_test()
        return 0
    if len(sys.argv) != 4:
        raise SystemExit("usage: wimt_live_device_report.py instrumentation.txt process-exit-code screen-verification.json")
    try:
        instrumentation = Path(sys.argv[1]).read_text(errors="replace") \
            if Path(sys.argv[1]).exists() else ""
        code = int(sys.argv[2])
        verification = Path(sys.argv[3]).read_text(errors="replace") \
            if Path(sys.argv[3]).exists() else ""
    except (OSError, ValueError) as exc:
        print(f"WIMT_LIVE_REPORT_ERROR: {exc}", file=sys.stderr)
        return 2
    report = make_report(instrumentation, code, verification)
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
