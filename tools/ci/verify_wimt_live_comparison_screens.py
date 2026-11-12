#!/usr/bin/env python3
"""Validate only fresh in-test app captures for each WIMT source comparison.

The PNGs are read from the installed app's private capture directory. Completed comparisons must
have matching UiAutomator hierarchies that identify the same train/date and both selected source
paths. Rejected source attempts get a diagnostic-only capture inventory and never count. No
screenshot is synthesized, cropped, OCR'd, or substituted from an old run.
"""
from pathlib import Path
import argparse
import json
import re
import sys
from datetime import datetime
from zoneinfo import ZoneInfo
import xml.etree.ElementTree as ET

from verify_wimt_cache_screens import read_capture

APP_PACKAGE = "in.opentrack.india"
MARKER = "WIMT_MULTI_ATTEMPT_JSON="
MIN_COMPARISONS = 3


def parse_attempts(text: str) -> list[dict]:
    attempts = []
    for line in text.splitlines():
        at = line.find(MARKER)
        if at < 0:
            continue
        payload = line[at + len(MARKER):].strip()
        try:
            value = json.loads(payload)
        except json.JSONDecodeError as exc:
            raise ValueError("malformed installed-test comparison result JSON") from exc
        if not isinstance(value, dict) or not isinstance(value.get("trainNo"), str):
            raise ValueError("installed-test comparison result is missing its train number")
        attempts.append(value)
    return attempts


def _hierarchy_text_by_id(path: Path, name: str) -> list[str]:
    root = ET.parse(path).getroot()
    values = []
    for node in root.iter():
        resource = node.attrib.get("resource-id", "")
        if resource.rsplit("/", 1)[-1] != name:
            continue
        if node.attrib.get("visible-to-user", "true").lower() != "true":
            continue
        values.append(node.attrib.get("text", "") + " " + node.attrib.get("content-desc", ""))
    return values


def _row_events(text: str, web_cache: bool) -> dict[str, dict[str, str]]:
    rows: dict[str, dict[str, str]] = {}
    line = re.compile(r"(?m)^\s*•\s*([A-Z0-9]{2,6})\s*·\s*(.+?)\s*$")
    arrival = re.compile(r"(?:^|·)\s*arr(?:ival)?\s+(\d{2}:\d{2}(?:\s+\([+-]\d+d\))?)", re.I)
    departure = re.compile(r"(?:^|·)\s*dep(?:arture)?\s+(\d{2}:\d{2}(?:\s+\([+-]\d+d\))?)", re.I)
    for match in line.finditer(text):
        code, body = match.group(1).upper(), match.group(2)
        fields = {}
        for field, pattern in (("arrival", arrival), ("departure", departure)):
            value = pattern.search(body)
            if value:
                fields[field] = re.sub(r"\s+", " ", value.group(1))
        if fields:
            rows[code] = fields
    if web_cache and "WIMT web note (unverified, not Act)" not in text:
        return {}
    if not web_cache and "Selected WIMT .in source-reported rows" not in text:
        return {}
    return rows


def _comparison(app: dict[str, dict[str, str]], web: dict[str, dict[str, str]]) -> dict:
    matched, mismatches, app_only, web_only = [], [], [], []
    overlap = 0
    for station in sorted(set(app) | set(web)):
        for field in ("arrival", "departure"):
            a = app.get(station, {}).get(field)
            w = web.get(station, {}).get(field)
            label = f"{station} {field}"
            if a is not None and w is not None:
                overlap += 1
                if a == w:
                    matched.append(f"{label}={a}")
                else:
                    mismatches.append(f"{label} app={a} web={w}")
            elif a is not None:
                app_only.append(f"{label} app={a}")
            elif w is not None:
                web_only.append(f"{label} web={w}")
    return {"overlap": overlap, "matched": matched, "mismatches": mismatches,
            "appOnly": app_only, "webOnly": web_only}


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


def _freshest_visible_row_update(text: str) -> tuple[int, str] | None:
    pattern = re.compile(
        r"(?i)row update (\d{1,2} [A-Za-z]{3} \d{4} \d{2}:\d{2}:\d{2} IST)")
    rows = []
    for match in pattern.finditer(text):
        raw = match.group(1)
        millis = _parse_ist_millis(raw)
        if millis is not None:
            rows.append((millis, raw))
    return max(rows, default=None, key=lambda item: item[0])


def _visible_hierarchy_text(path: Path) -> str:
    root = ET.parse(path).getroot()
    return "\n".join(
        " ".join(node.attrib.get(key, "") for key in ("text", "content-desc"))
        for node in root.iter()
        if node.attrib.get("visible-to-user", "true").lower() == "true"
    )


def _diagnose_attempt_captures(folder: Path, attempt: dict) -> dict:
    train = attempt.get("trainNo")
    date = attempt.get("serviceDate")
    names = attempt.get("screenshots")
    if not isinstance(names, list):
        names = []
    captures = []
    for name in names:
        item = {"name": name, "pngAndAppHierarchyValid": False,
                "visibleContextChecks": {}, "diagnosticValid": False}
        if not isinstance(train, str) or not isinstance(name, str):
            item["error"] = "attempt does not identify a safe screenshot name and train"
            captures.append(item)
            continue
        match = re.fullmatch(
            rf"wimt-compare-{re.escape(train)}-(before|app-live|app-details|web-cache|web-details|final)",
            name,
        )
        if not match:
            item["error"] = "screenshot name is not scoped to this train's installed-test capture"
            captures.append(item)
            continue
        role = match.group(1)
        item["role"] = role
        try:
            image, _, dimensions = read_capture(folder, name)
            visible_text = _visible_hierarchy_text(image.with_suffix(".xml"))
        except (OSError, ValueError, ET.ParseError) as exc:
            item["error"] = str(exc)[:220]
            captures.append(item)
            continue
        checks: dict[str, bool] = {}
        if role in ("before", "app-live", "final"):
            train_nodes = _hierarchy_text_by_id(image.with_suffix(".xml"), "trainNo")
            train_pattern = re.compile(rf"(?<!\d){re.escape(train)}(?!\d)")
            checks["trainVisible"] = any(train_pattern.search(value) for value in train_nodes)
            if role in ("before", "app-live") and isinstance(date, str):
                date_nodes = _hierarchy_text_by_id(image.with_suffix(".xml"), "runDate")
                checks["serviceDateVisible"] = any(date.casefold() in value.casefold()
                                                    for value in date_nodes)
        if role == "app-live":
            checks["selectedDirectSourceVisible"] = \
                "Selected WIMT .in live-status feed".casefold() in visible_text.casefold()
            checks["sourceSummaryVisible"] = bool(_hierarchy_text_by_id(
                image.with_suffix(".xml"), "sourceSummary"))
        elif role == "app-details":
            checks["directResponseDetailsVisible"] = \
                "Selected WIMT .in live-status response".casefold() in visible_text.casefold()
        elif role == "web-cache":
            checks["selectedWebCacheVisible"] = \
                "Selected WIMT web cache".casefold() in visible_text.casefold()
        elif role == "web-details":
            checks["webCacheDetailsVisible"] = "WIMT web cache:".casefold() in visible_text.casefold()
            checks["webCacheRowsVisible"] = \
                "Selected WIMT web-cache rows".casefold() in visible_text.casefold()
        item.update({
            "pngAndAppHierarchyValid": True,
            "pngBytes": image.stat().st_size,
            "dimensions": list(dimensions),
            "visibleContextChecks": checks,
            "diagnosticValid": all(checks.values()) if checks else True,
        })
        captures.append(item)
    return {
        "trainNo": train,
        "serviceDate": date,
        "sourceVerdict": attempt.get("verdict"),
        "diagnosticOnly": True,
        "countedTowardAcceptance": False,
        "listedCaptureCount": len(names),
        "validPngAndAppHierarchyCount": sum(
            row["pngAndAppHierarchyValid"] for row in captures),
        "contextCheckedCaptureCount": sum(row["diagnosticValid"] for row in captures),
        "captures": captures,
    }


def verify(folder: Path, device_log: str) -> dict:
    attempts = parse_attempts(device_log)
    verified = []
    diagnostic_attempts = []
    completed_attempt_count = 0
    for attempt in attempts:
        if attempt.get("verdict") != "COMPARISON_COMPLETE":
            diagnostic_attempts.append(_diagnose_attempt_captures(folder, attempt))
            continue
        completed_attempt_count += 1
        train = attempt["trainNo"]
        date = attempt.get("serviceDate")
        if not re.fullmatch(r"[0-9]{4,5}", train):
            raise ValueError("completed comparison has an invalid train number")
        if attempt.get("appTrainNumberMatched") is not True or \
                attempt.get("webRequestTrainNumber") != train or \
                attempt.get("webRequestTrainNumberMatchesApp") is not True:
            raise ValueError(f"{train}: installed app and web-cache request train numbers do not match")
        if not isinstance(date, str) or not date:
            raise ValueError(f"{train}: completed comparison has no app service date")
        names = {
            "appLive": f"wimt-compare-{train}-app-live",
            "appDetails": f"wimt-compare-{train}-app-details",
            "webCache": f"wimt-compare-{train}-web-cache",
            "webDetails": f"wimt-compare-{train}-web-details",
        }
        logged_screenshots = attempt.get("screenshots")
        if not isinstance(logged_screenshots, list) or \
                not all(isinstance(name, str) for name in logged_screenshots) or \
                not set(names.values()).issubset(set(logged_screenshots)):
            raise ValueError(f"{train}: test result does not list every required in-app capture")
        captures = {key: read_capture(folder, name) for key, name in names.items()}
        app_image, app_text, app_dimensions = captures["appLive"]
        direct_image, direct_text, direct_dimensions = captures["appDetails"]
        web_image, web_text, web_dimensions = captures["webCache"]
        web_details_image, web_details_text, web_details_dimensions = captures["webDetails"]

        train_nodes = _hierarchy_text_by_id(app_image.with_suffix(".xml"), "trainNo")
        date_nodes = _hierarchy_text_by_id(app_image.with_suffix(".xml"), "runDate")
        train_pattern = re.compile(rf"(?<!\d){re.escape(train)}(?!\d)")
        if not any(train_pattern.search(value) for value in train_nodes) or \
                not any(date.lower() in value.lower() for value in date_nodes):
            raise ValueError(f"{train}/{date}: actual app-window capture does not show that train and date")
        if "Selected WIMT .in live-status feed" not in app_text or \
                "sourceSummary" not in app_text:
            raise ValueError(f"{train}: installed-app screenshot does not show the selected direct WIMT source")
        if "Selected WIMT .in live-status response" not in direct_text or \
                "HTTP 200" not in direct_text or \
                "Run date echoed and matched: true" not in direct_text or \
                "both route endpoints echoed and matched: true" not in direct_text or \
                "fresh source stamp; not event time" not in direct_text or \
                "current-station field claims" not in direct_text:
            raise ValueError(f"{train}/{date}: direct-source details do not substantiate date, route, freshness, and current-station gates")
        if "Selected WIMT web cache" not in web_text:
            raise ValueError(f"{train}: comparison screenshot does not show the selected WIMT web-cache path")
        if "WIMT web cache:" not in web_details_text or \
                "HTTP 200" not in web_details_text or \
                "Selected WIMT web-cache rows" not in web_details_text or \
                f"run date {date}" not in web_details_text or \
                "matched requested route" not in web_details_text or \
                "row update " not in web_details_text or \
                not re.search(r"row update \d{1,2} [A-Za-z]{3} \d{4} \d{2}:\d{2}:\d{2} IST", web_details_text):
            raise ValueError(f"{train}/{date}: comparison details do not show same-date, route-matched cache rows with a dated update")
        if attempt.get("webTrainNumberEchoMatched") is False:
            raise ValueError(f"{train}: cache response echoed a different train number")
        if attempt.get("webTrainNumberEchoMatched") is None and \
                f"response train number not echoed; request used train {train}" not in web_details_text:
            raise ValueError(f"{train}: cache details do not confirm the exact requested train number")
        if attempt.get("webTrainNumberEchoMatched") is True and \
                "response train number matched requested train" not in web_details_text:
            raise ValueError(f"{train}: response's echoed train number is not visible in its comparison details")
        if attempt.get("webFresh") is not True or attempt.get("directFresh") is not True:
            raise ValueError(f"{train}/{date}: logged freshness gate is not satisfied")
        freshest_update = _freshest_visible_row_update(web_details_text)
        if freshest_update is None:
            raise ValueError(f"{train}/{date}: web-cache screenshot has no parseable row-update timestamp")
        if attempt.get("webFreshestRowUpdatedAt") != freshest_update[1]:
            raise ValueError(f"{train}/{date}: logged freshest row-update timestamp differs from the captured details")
        checked_at = _parse_ist_millis(attempt.get("webFreshnessCheckedAt"))
        logged_age = attempt.get("webFreshestRowAgeSeconds")
        if checked_at is None or not isinstance(logged_age, int) or isinstance(logged_age, bool):
            raise ValueError(f"{train}/{date}: exact web-cache freshness check time is missing")
        age_millis = checked_at - freshest_update[0]
        if age_millis < 0 or age_millis > 600_000 or age_millis // 1_000 != logged_age:
            raise ValueError(f"{train}/{date}: logged web-cache row age does not match the captured update time")
        direct_row_count = re.search(
            r"Selected WIMT \.in source-reported rows \((\d+)\):", direct_text)
        web_row_count = re.search(
            r"WIMT web cache: (\d+) source-reported web-cache time row\(s\)", web_details_text)
        if not direct_row_count or int(direct_row_count.group(1)) != attempt.get("directSourceRows") or \
                not web_row_count or int(web_row_count.group(1)) != attempt.get("webSourceRows"):
            raise ValueError(f"{train}/{date}: row counts in the test result differ from the source-details captures")

        app_rows = _row_events(direct_text, web_cache=False)
        web_rows = _row_events(web_details_text, web_cache=True)
        comparison = _comparison(app_rows, web_rows)
        logged = {
            "overlap": attempt.get("overlappingEventFields"),
            "matched": attempt.get("matchedEventFields"),
            "mismatches": attempt.get("mismatchFields", []),
            "appOnly": attempt.get("appOnlyEventFields", []),
            "webOnly": attempt.get("webOnlyEventFields", []),
        }
        captured = {
            "overlap": comparison["overlap"],
            "matched": len(comparison["matched"]),
            "mismatches": comparison["mismatches"],
            "appOnly": comparison["appOnly"],
            "webOnly": comparison["webOnly"],
        }
        if logged != captured:
            raise ValueError(f"{train}/{date}: instrumented comparison disagrees with the captured UI hierarchy")
        if captured["overlap"] < 1:
            raise ValueError(f"{train}/{date}: no same-station event field was compared")

        verified.append({
            "trainNo": train,
            "serviceDate": date,
            "screenshots": [
                {"role": key, "name": captures[key][0].name,
                 "bytes": captures[key][0].stat().st_size,
                 "dimensions": list(captures[key][2])}
                for key in names
            ],
            "trainVisible": True,
            "serviceDateVisible": True,
            "directSourceVisible": True,
            "webCacheComparisonVisible": True,
            "webFreshness": {
                "freshestRowUpdatedAt": freshest_update[1],
                "checkedAt": attempt["webFreshnessCheckedAt"],
                "freshestRowAgeSeconds": logged_age,
                "validatedAgainstCapturedDetails": True,
            },
            "overlappingEventFields": captured["overlap"],
            "matchedEventFields": captured["matched"],
            "mismatchFields": captured["mismatches"],
            "appOnlyEventFields": captured["appOnly"],
            "webOnlyEventFields": captured["webOnly"],
        })

    status = "VERIFIED" if verified else (
        "NO_COMPLETED_COMPARISONS_TO_VERIFY" if completed_attempt_count == 0 else
        "NO_VERIFIED_COMPARISONS"
    )
    reason = ("Every completed comparison has matching, validated installed-app captures."
              if verified else
              "No comparison reached COMPARISON_COMPLETE, so the acceptance screenshot checks "
              "were not applicable; diagnostic captures below never count toward acceptance."
              if completed_attempt_count == 0 else
              "No completed comparison had a verified capture set.")
    return {
        "verified": bool(verified),
        "verificationStatus": status,
        "verificationReason": reason,
        "appPackage": APP_PACKAGE,
        "captureSource": "fresh in-test UiAutomator screenshots and matching app-window hierarchies",
        "minimumComparisons": MIN_COMPARISONS,
        "acceptedComparisonCount": len(verified),
        "comparisons": verified,
        "diagnosticOnlyAttempts": diagnostic_attempts,
        "pixelTextVerified": False,
        "pixelTextNote": "PNG bytes are fully validated; text claims are checked against matching installed-app UI hierarchies, not OCR.",
    }


def self_test() -> None:
    payload = {"trainNo": "12301", "verdict": "COMPARISON_COMPLETE"}
    parsed = parse_attempts("I WimtMultiTrain: " + MARKER + json.dumps(payload))
    assert parsed == [payload]
    direct_details = (
        "Selected WIMT .in source-reported rows (1):\n"
        "• SBC · arr 10:20 · dep 10:22\n"
    )
    web_details = (
        "WIMT web cache: 1 source-reported web-cache time row(s)\n"
        "Selected WIMT web-cache rows · unverified, not railway ACTUAL:\n"
        "• SBC · run date 12 Oct 2026 · arrival 10:20 [field actual_arrival_tm] · "
        "row update 12 Oct 2026 10:21:00 IST · WIMT web note (unverified, not Act)\n"
    )
    assert _row_events(direct_details, web_cache=False) == {
        "SBC": {"arrival": "10:20", "departure": "10:22"}
    }
    assert _row_events(web_details, web_cache=True) == {"SBC": {"arrival": "10:20"}}
    assert re.search(r"row update \d{1,2} [A-Za-z]{3} \d{4} \d{2}:\d{2}:\d{2} IST", web_details)
    assert re.search(r"Selected WIMT \.in source-reported rows \((\d+)\):", direct_details)
    assert re.search(r"WIMT web cache: (\d+) source-reported web-cache time row\(s\)", web_details)
    assert _freshest_visible_row_update(web_details) == (
        _parse_ist_millis("12 Oct 2026 10:21:00 IST"), "12 Oct 2026 10:21:00 IST")
    assert _parse_ist_millis("12 Oct 2026 10:21:40.125 IST") - \
        _parse_ist_millis("12 Oct 2026 10:21:00 IST") == 40_125
    assert _comparison({"SBC": {"arrival": "10:20"}},
                       {"SBC": {"arrival": "10:20", "departure": "10:22"}}) == {
        "overlap": 1, "matched": ["SBC arrival=10:20"], "mismatches": [],
        "appOnly": [], "webOnly": ["SBC departure web=10:22"],
    }
    empty = verify(Path("."), "")
    assert empty["verified"] is False
    assert empty["verificationStatus"] == "NO_COMPLETED_COMPARISONS_TO_VERIFY"
    rejected = {
        "trainNo": "12301", "serviceDate": "12 Oct 2026",
        "verdict": "WEB_CACHE_SOURCE_NOT_QUALIFIED",
        "screenshots": ["wimt-compare-12301-before"],
    }
    diagnostic = verify(Path("/missing-wimt-captures"),
                        MARKER + json.dumps(rejected))
    assert diagnostic["verified"] is False
    assert diagnostic["diagnosticOnlyAttempts"][0]["countedTowardAcceptance"] is False
    assert diagnostic["diagnosticOnlyAttempts"][0]["validPngAndAppHierarchyCount"] == 0
    print("WIMT live comparison screenshot verifier self-test passed")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", type=Path, nargs="?")
    parser.add_argument("--device-log", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.evidence is None or args.device_log is None:
        parser.error("evidence and --device-log are required")
    try:
        result = verify(args.evidence, args.device_log.read_text(encoding="utf-8", errors="replace"))
    except (OSError, ValueError, ET.ParseError) as exc:
        print(f"WIMT_LIVE_COMPARISON_SCREEN_NOT_VERIFIED: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
