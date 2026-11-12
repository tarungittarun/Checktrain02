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
import xml.etree.ElementTree as ET

from verify_wimt_cache_screens import read_capture

APP_PACKAGE = "in.opentrack.india"
REQUIRED = {
    "before": "wimt-live-before-selected-source",
    "after": "wimt-live-after-selected-source",
    "details": "wimt-live-source-details",
}
ROW_PATTERN = r"Selected WIMT \.in source-reported rows \((\d+)\):"


def visible_wimt_time_cell_count(hierarchy: ET.Element) -> int:
    """Count real visible table clock views; row accessibility labels repeat child clocks."""
    count = 0
    for node in hierarchy.iter():
        resource_id = node.attrib.get("resource-id", "")
        if not resource_id.startswith(f"{APP_PACKAGE}:id/") or \
                resource_id.rsplit("/", 1)[-1] not in {"liveArr", "liveDep"}:
            continue
        if node.attrib.get("visible-to-user", "true").lower() != "true":
            continue
        description = node.attrib.get("content-desc", "")
        if not re.search(r"WIMT \.in source-reported (?:arrival|departure) ",
                         description, re.IGNORECASE):
            continue
        if description.lower().endswith("unknown"):
            continue
        count += 1
    return count


def self_test() -> None:
    xml = (
        '<hierarchy>'
        '<node package="in.opentrack.india" resource-id="in.opentrack.india:id/stopRow" '
        'visible-to-user="true" content-desc="WIMT .in source-reported arrival 08:05; '
        'WIMT .in source-reported departure 08:09" />'
        '<node package="in.opentrack.india" resource-id="in.opentrack.india:id/liveArr" '
        'content-desc="WIMT .in source-reported arrival 08:05" />'
        '<node package="in.opentrack.india" resource-id="in.opentrack.india:id/liveDep" '
        'content-desc="WIMT .in source-reported departure 08:09" />'
        '<node package="in.opentrack.india" resource-id="in.opentrack.india:id/liveDep" '
        'visible-to-user="true" content-desc="WIMT .in source-reported departure unknown" />'
        '<node package="in.opentrack.india" resource-id="in.opentrack.india:id/liveArr" '
        'visible-to-user="false" content-desc="WIMT .in source-reported arrival 07:55" />'
        '<node package="other.app" resource-id="other.app:id/liveArr" '
        'visible-to-user="true" content-desc="WIMT .in source-reported arrival 07:45" />'
        '</hierarchy>'
    )
    hierarchy = ET.fromstring(xml)
    assert visible_wimt_time_cell_count(hierarchy) == 2
    print("WIMT live screenshot verifier self-test passed: counts visible table cells, not duplicated row labels")

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
    after_hierarchy = ET.parse(captures["after"][0].with_suffix(".xml")).getroot()
    cell_count = visible_wimt_time_cell_count(after_hierarchy)
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
    parser.add_argument("evidence", type=Path, nargs="?")
    parser.add_argument("--accepted", action="store_true",
                        help="require fresh, run/route-matched WIMT live stop clocks in the app hierarchy")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.evidence is None:
        parser.error("evidence directory is required unless --self-test is used")
    try:
        result = verify(args.evidence, args.accepted)
    except (OSError, ValueError) as exc:
        print(f"WIMT_LIVE_SCREEN_NOT_VERIFIED: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
