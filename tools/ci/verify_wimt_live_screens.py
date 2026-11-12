#!/usr/bin/env python3
"""Verify only in-test screenshots and UI hierarchy from the installed WIMT `.in` API trial.

PNG files are validated as images, not OCR'd or synthesized. Text verdicts are read from the
matching UiAutomator hierarchy saved by the running app test, never from a host/launcher capture.
"""
from pathlib import Path
import argparse
import json
import re
import sys

from verify_wimt_cache_screens import read_capture

APP_PACKAGE = "in.opentrack.india"
REQUIRED = {
    "before": "wimt-live-before-selected-source",
    "after": "wimt-live-after-selected-source",
    "details": "wimt-live-source-details",
}
ROW_PATTERN = r"Selected WIMT \.in source-reported rows \((\d+)\):"


def verify(folder: Path, accepted: bool = False) -> dict:
    captures = {label: read_capture(folder, name) for label, name in REQUIRED.items()}
    before = captures["before"][1]
    after = captures["after"][1]
    details = captures["details"][1]

    if "sourceSpinner" not in before:
        raise ValueError("before-request in-app screenshot lacks the tracking source selector")
    if "Selected WIMT .in live-status feed" not in after:
        raise ValueError("after-request in-app hierarchy does not identify the selected `.in` feed")
    if "sourceSummary" not in after or "WIMT .in" not in after:
        raise ValueError("after-request in-app hierarchy lacks the selected WIMT summary")
    if "Selected WIMT .in live-status response" not in details:
        raise ValueError("source-details hierarchy does not show the selected `.in` response")

    row_match = re.search(ROW_PATTERN, details)
    summary_match = re.search(r"WIMT \.in: (\d+) source-reported stop row\(s\)", after)
    rows = int(row_match.group(1)) if row_match else 0
    summary_rows = int(summary_match.group(1)) if summary_match else 0
    http_match = re.search(r"\bHTTP ([1-5][0-9]{2})\b", details)
    http_status = f"HTTP {http_match.group(1)}" if http_match else None
    cell_count = len(re.findall(r"WIMT \.in source-reported (?:arrival|departure) ", after))
    run_matched = "Run date echoed and matched: true" in details
    route_matched = "both route endpoints echoed and matched: true" in details
    fresh_stamp = "fresh source stamp; not event time" in details and \
        "upstream update stamp is fresh" in after

    accepted_in_hierarchy = rows > 0 and summary_rows > 0 and \
        "source-reported stop row" in after and cell_count > 0 and \
        http_status == "HTTP 200" and run_matched and route_matched and fresh_stamp
    if accepted and not accepted_in_hierarchy:
        raise ValueError("accepted run is not backed by visible fresh, run/route-matched `.in` rows and WIMT-attributed table clocks")

    return {
        "verified": True,
        "appPackage": APP_PACKAGE,
        "captureSource": "in-test UiAutomator screenshot plus matching app-window hierarchy",
        "acceptedDataVerifiedInAppHierarchy": accepted and accepted_in_hierarchy,
        "sourceRowsInDetails": rows,
        "sourceRowsInSummary": summary_rows,
        "wimtAttributedVisibleTimeCells": cell_count,
        "httpStatusVisibleInDetails": http_status,
        "runDateMatchedVisible": run_matched,
        "routeEndpointsMatchedVisible": route_matched,
        "freshSourceStampVisible": fresh_stamp,
        "pixelTextVerified": False,
        "screenshots": [
            {"name": REQUIRED[key] + ".png", "bytes": captures[key][0].stat().st_size,
             "dimensions": list(captures[key][2])}
            for key in REQUIRED
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--accepted", action="store_true",
                        help="require fresh, run/route-matched WIMT live stop clocks in the app hierarchy")
    args = parser.parse_args()
    try:
        result = verify(args.evidence, args.accepted)
    except (OSError, ValueError) as exc:
        print(f"WIMT_LIVE_SCREEN_NOT_VERIFIED: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
