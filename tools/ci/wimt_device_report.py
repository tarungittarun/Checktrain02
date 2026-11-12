#!/usr/bin/env python3
"""Bounded verdict for the strict .org.in APK device journey; no raw diary publication."""
import datetime as dt
import json
import re
import sys
from pathlib import Path

HOST = "whereismytrain.org.in"
EXPECTED_TRAIN = "16227"
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))
EPOCH_IST = dt.datetime(1970, 1, 1, tzinfo=IST)
EPOCH_UTC = dt.datetime(1970, 1, 1, tzinfo=dt.timezone.utc)
IST_OFFSET_MS = 19_800_000


def _ist_time(epoch_ms: int | None) -> str | None:
    if epoch_ms is None:
        return None
    return (EPOCH_UTC + dt.timedelta(milliseconds=epoch_ms)).astimezone(IST).isoformat(
        timespec="seconds")


def verdict(instrumentation: str, diary: str, process_rc: int) -> dict:
    attempted = re.findall(r"\[HTTP\] GET whereismytrain\.org\.in try#\d+ -> ([^\r\n]+)", diary)
    evidence = [line for line in diary.splitlines()
                if "[EVIDENCE] source=whereismytrain.org.in (website)" in line]
    latest = evidence[-1] if evidence else ""

    def token(pattern: str) -> str | None:
        match = re.search(pattern, latest)
        return match.group(1) if match else None

    transport = token(r"\btransport=([A-Z_]+)")
    parsed = token(r"\bparsed=([A-Z_]+)")
    identity = token(r"\brunIdentity=([A-Z_]+)")
    rows = token(r"\bsiteRows=(\d+)")
    train = token(r"\btrain=(\d{5})\b")
    origin_day_raw = token(r"\boriginDay=(\d{1,8})\b")
    site_claim = re.search(r"\bsiteClaim=([A-Z0-9]{1,6}):(-?\d{1,5})\b", latest)
    published_raw = token(r"\bpagePublishedAt=(\d{1,16})\b")
    checked_raw = token(r"\bcheckedAt=(\d{1,16})\b")
    origin_day = int(origin_day_raw) if origin_day_raw is not None else None
    event_minute = int(site_claim.group(2)) if site_claim else None
    published_at = int(published_raw) if published_raw is not None else None
    checked_at = int(checked_raw) if checked_raw is not None else None
    event_at = ((origin_day * 1440 + event_minute) * 60_000 - IST_OFFSET_MS
                if origin_day is not None and event_minute is not None else None)
    page_age = ((checked_at - published_at) // 1_000
                if checked_at is not None and published_at is not None else None)
    event_age = ((checked_at - event_at) // 1_000
                 if checked_at is not None and event_at is not None else None)
    try:
        run_date = (EPOCH_IST + dt.timedelta(days=origin_day)).date().isoformat() \
            if origin_day is not None else None
        claim_time = (EPOCH_IST + dt.timedelta(days=origin_day, minutes=event_minute)
                      ).isoformat(timespec="seconds") \
            if origin_day is not None and event_minute is not None else None
    except (OverflowError, ValueError):
        run_date = None
        claim_time = None

    test_passed = process_rc == 0 and bool(re.search(r"OK \(1 test\)", instrumentation)) and \
        "FAILURES!!!" not in instrumentation and "INSTRUMENTATION_FAILED" not in instrumentation
    site_answered = any("answered " in x for x in attempted)
    wire_refused = any("HTTP 403" in x or "HTTP 429" in x for x in attempted)
    # The opt-in diary may not have been collected from an emulator. The test's
    # *visible UI* failure can still establish that the app displayed a website
    # refusal. Do not invent a wire request count when the wire receipt is absent.
    ui_refused = bool(re.search(r"Website refused request \(HTTP (?:403|429)\)", instrumentation))
    refused = wire_refused or ui_refused
    provenance_complete = (
        train == EXPECTED_TRAIN and origin_day is not None and origin_day >= 0 and
        run_date is not None and identity == "INFERRED" and parsed == "SITE_COMPARISON" and
        site_claim is not None and published_at is not None and checked_at is not None and
        claim_time is not None and page_age is not None and 0 <= page_age <= 300 and
        event_at is not None and event_age is not None and event_age >= 0 and
        event_at <= published_at <= checked_at
    )
    accepted = test_passed and site_answered and not refused and \
        transport == "NETWORK_ANSWER" and rows is not None and int(rows) > 0 and \
        provenance_complete
    reason = ("PASS_SITE_CARD_AND_DEVICE_UI" if accepted else
              "WEBSITE_REFUSED_WIRE" if wire_refused else
              "WEBSITE_REFUSED_UI_DIARY_UNAVAILABLE" if ui_refused else
              "DEVICE_TEST_NOT_EXECUTED" if not instrumentation else
              "SITE_REPLY_OR_SOURCE_QUALIFICATION_MISSING" if site_answered else
              "NO_WEBSITE_NETWORK_ANSWER")
    return {"verdict": reason, "accepted": accepted, "instrumentationPassed": test_passed,
            "websiteGetCount": len(attempted) if attempted else None, "websiteAnswered": site_answered,
            "websiteRefused": refused, "wireReceiptAvailable": bool(attempted),
            "source": HOST, "sourceTransport": transport or "unknown",
            "siteRows": int(rows) if rows else 0,
            "trainNumber": train or "unknown", "originDay": origin_day,
            "originRunDateIst": run_date,
            "runDateBasis": "inferred_from_selected_timetable; site does not publish run date",
            "runIdentity": identity or "unknown",
            "siteClaimStation": site_claim.group(1) if site_claim else "unknown",
            "siteClaimMinuteFromOrigin": event_minute,
            "siteClaimTimeIst": claim_time,
            "siteClaimAgeSecondsAtCheck": event_age,
            "pagePublishedAtIst": _ist_time(published_at),
            "pageAgeSecondsAtCheck": page_age,
            "checkedAtIst": _ist_time(checked_at),
            "provenanceComplete": provenance_complete,
            "googlePhoneAppFeed": False, "browserEngineRequest": True,
            "websiteGetCountScope": "main_document_navigation_only",
            "note": "Website-published station claim, not independently verified movement/GPS; "
                    "emulator WebView, not owner's phone or Google WIMT app"}


def self_test() -> None:
    published = int((dt.datetime(2026, 11, 12, 21, 5, tzinfo=IST) - EPOCH_UTC)
                    .total_seconds() * 1_000)
    checked = published + 30_000
    good = ("11-05 [HTTP] GET whereismytrain.org.in try#1 -> answered 108395B\n"
            "11-05 [EVIDENCE] source=whereismytrain.org.in (website) train=16227 "
            "originDay=20769 transport=NETWORK_ANSWER bytes=108395 sha256_16=0123456789abcdef "
            "parsed=SITE_COMPARISON runIdentity=INFERRED primaryActuals=0 primaryMarker=none "
            "siteRows=10 siteClaim=RRB:1217 pagePublishedAt=" + str(published) +
            " checkedAt=" + str(checked) + "\n")
    result = verdict("OK (1 test)", good, 0)
    assert result["accepted"]
    assert result["originRunDateIst"] == "2026-11-12"
    assert result["siteClaimTimeIst"] == "2026-11-12T20:17:00+05:30"
    assert result["siteClaimAgeSecondsAtCheck"] == 2_910
    assert result["pageAgeSecondsAtCheck"] == 30
    denied = good.replace("answered 108395B", "HTTP HTTP 403").replace(
        "transport=NETWORK_ANSWER", "transport=FAILED_OR_UNUSABLE").replace(
        "siteRows=10 siteClaim=RRB:1217", "siteRows=0 siteClaim=none")
    assert verdict("OK (1 test)", denied, 0)["verdict"] == "WEBSITE_REFUSED_WIRE"
    no_diary = verdict("java.lang.AssertionError: warning='Website refused request (HTTP 403)'\n"
                       "FAILURES!!!\nTests run: 1,  Failures: 1", "", 0)
    assert no_diary["verdict"] == "WEBSITE_REFUSED_UI_DIARY_UNAVAILABLE"
    assert no_diary["websiteGetCount"] is None and not no_diary["accepted"]
    unrelated_diary = verdict("Website refused request (HTTP 403)", "other app log entry", 1)
    assert unrelated_diary["websiteGetCount"] is None
    assert not unrelated_diary["wireReceiptAvailable"]
    assert not verdict("FAILURES!!!", good, 0)["accepted"]
    assert not verdict("OK (1 test)", good, 1)["accepted"]
    assert not verdict("", good, 0)["accepted"]
    stale = good.replace(f"pagePublishedAt={published}", f"pagePublishedAt={checked - 301_000}")
    assert not verdict("OK (1 test)", stale, 0)["accepted"]
    later_claim = good.replace("siteClaim=RRB:1217", "siteClaim=RRB:1300")
    assert not verdict("OK (1 test)", later_claim, 0)["accepted"]
    wrong_train = good.replace("train=16227", "train=20652")
    assert not verdict("OK (1 test)", wrong_train, 0)["accepted"]
    assert "0123456789abcdef" not in json.dumps(result)
    print("offline strict gate, train/run/event/page freshness and diary-redaction tests passed")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 4:
        ins = Path(sys.argv[1]).read_text(errors="replace") if Path(sys.argv[1]).exists() else ""
        diary = Path(sys.argv[2]).read_text(errors="replace") if Path(sys.argv[2]).exists() else ""
        report = verdict(ins, diary, int(sys.argv[3]))
        print(json.dumps(report, sort_keys=True))
        if not report["accepted"]:
            sys.exit(1)
    else:
        raise SystemExit("usage: wimt_device_report.py instrumentation.txt private-diary.txt process-exit-code")
