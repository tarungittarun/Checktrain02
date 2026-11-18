#!/usr/bin/env python3
"""Produce a bounded, source-honest result for the installed `.in` cache test.

No response body, cookie, browser identity, or unrelated private diary is read or published.
"""
from pathlib import Path
import json
import re
import sys

PASS_LINE = "WIMT_CACHE_DEVICE_VERDICT=PASS_DATED_CACHE_ROWS_SHOWN_AS_UNVERIFIED_COMPARISON"


def make_report(instrumentation: str, return_code: int,
                screen_verification: str = "") -> dict:
    exactly_one = bool(re.search(r"\bOK \(1 test\)", instrumentation)) and \
        "FAILURES!!!" not in instrumentation
    test_candidate = return_code == 0 and exactly_one and PASS_LINE in instrumentation
    row = re.search(r"WIMT_CACHE_DEVICE_RESULT[^\n]*candidateRows=(\d+)[^\n]*http200=(true|false)",
                    instrumentation, re.IGNORECASE)
    rows = int(row.group(1)) if row else None
    http_200 = (row.group(2).lower() == "true") if row else None
    screen = {}
    try:
        screen = json.loads(screen_verification)
    except (json.JSONDecodeError, TypeError):
        pass
    required_screens = {
        "wimt-cache-before-selected-source.png",
        "wimt-cache-after-selected-source.png",
        "wimt-cache-source-details.png",
    }
    screen_items = screen.get("screenshots", [])
    if not isinstance(screen_items, list):
        screen_items = []
    screen_names = {item.get("name") for item in screen_items
                    if isinstance(item, dict)}
    screen_ok = screen.get("verified") is True and \
        screen.get("appPackage") == "in.opentrack.india" and \
        screen.get("captureSource") == \
            "in-test UiAutomator screenshot plus matching app-window hierarchy" and \
        required_screens.issubset(screen_names)
    if rows is None and isinstance(screen.get("candidateRows"), int):
        rows = screen["candidateRows"]
    http_status = screen.get("httpStatusVisibleInDetails")
    if http_200 is None:
        http_200 = http_status == "HTTP 200"
    accepted = test_candidate and screen_ok and rows is not None and rows > 0 and \
        http_200 is True and http_status == "HTTP 200" and \
        screen.get("acceptedDataVerifiedInAppHierarchy") is True

    text = instrumentation.lower()
    if accepted:
        verdict = "PASS_DATED_CACHE_ROWS_SHOWN_AS_UNVERIFIED_COMPARISON"
        reason = "Installed APK fetched dated cache data and displayed at least one strictly parsed row only as an unverified comparison."
    elif http_status == "HTTP 403" or "http 403" in text:
        verdict = "HTTP_403_REFUSED"
        reason = "The selected cache request was refused; no cache data acceptance is claimed."
    elif http_status == "HTTP 429" or "http 429" in text:
        verdict = "HTTP_429_RATE_LIMITED"
        reason = "The selected cache request was rate-limited; no cache data acceptance is claimed."
    elif (http_status == "HTTP 200" and rows == 0) or ("http 200" in text and rows == 0):
        verdict = "HTTP_200_NO_QUALIFYING_ROWS"
        reason = "An HTTP answer did not produce a strictly admissible source-reported row."
    elif "sockettimeoutexception" in text or "connectexception" in text or "timed out" in text:
        verdict = "TRANSPORT_FAILED"
        reason = "The installed-app request did not complete; no cache data acceptance is claimed."
    elif return_code != 0 or not exactly_one:
        verdict = "DEVICE_TEST_FAILED_OR_NOT_EXECUTED"
        reason = "The installed-app strict acceptance assertion did not pass. See the retained instrumentation output."
    else:
        verdict = "STRICT_CACHE_ACCEPTANCE_NOT_PROVEN"
        reason = "A green runner summary alone is insufficient; the strict row/provenance/screenshot evidence is absent."
    return {
        "source": "whereismytrain.in/cache/live_status (undocumented web cache)",
        "notTheSource": ["whereismytrain.org.in website", "Google Where Is My Train Android app"],
        "accepted": accepted,
        "verdict": verdict,
        "installedApkTestPassed": exactly_one and return_code == 0,
        "candidateRows": rows,
        "http200VisibleInSourceDetails": http_200,
        "inAppScreensVerified": screen_ok,
        "acceptedDataVerifiedInAppHierarchy": screen_ok and
            screen.get("acceptedDataVerifiedInAppHierarchy") is True,
        "pixelTextVerified": False,
        "reason": reason,
        "returnCode": return_code,
    }


def main() -> int:
    if len(sys.argv) not in (3, 4):
        raise SystemExit("usage: wimt_cache_device_report.py <instrumentation.txt> <exit-code> [screen-verification.json]")
    try:
        text = Path(sys.argv[1]).read_text(errors="replace")
        rc = int(sys.argv[2])
        screen = Path(sys.argv[3]).read_text(errors="replace") if len(sys.argv) == 4 else ""
    except (OSError, ValueError) as exc:
        print(f"WIMT_CACHE_REPORT_ERROR: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(make_report(text, rc, screen), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
