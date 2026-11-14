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
    detached_rows = token(r"\bdetachedSiteClaims=(\d+)")
    site_claim_kind = token(r"\bsiteClaimKind=([A-Z_]+)")
    site_route = token(r"\bsiteRoute=([A-Z0-9>]+)")
    site_page_endpoints = token(r"\bsitePageEndpoints=([A-Za-z0-9>]+)")
    site_page_labels = token(r"\bsitePageLabels=([^\s]+)")
    selected_route = token(r"\bselectedRoute=([A-Z0-9>]+)")
    site_card_containers = token(r"\bsiteCardContainers=(\d+)")
    site_date_matched = token(r"\bsiteDateMatched=(true|false)") == "true"
    site_page_run_date = token(r"\bsitePageRunDate=(unknown|\d{4}-\d{2}-\d{2})")
    site_shape = token(r"\bsiteShape=([^\s]{1,1800})")
    live_identity_route_match = token(r"\bliveIdentityRouteMatch=(true|false)") == "true"
    claim_time_basis = token(r"\bsiteClaimTimeBasis=([A-Za-z_;]+)")
    claim_at_raw = token(r"\bsiteClaimAt=(\d{1,16})\b")
    train = token(r"\btrain=(\d{5})\b")
    origin_day_raw = token(r"\boriginDay=(\d{1,8})\b")
    site_claim = re.search(r"\bsiteClaim=([A-Z0-9]{1,6}):(-?\d{1,5})\b", latest)
    published_raw = token(r"\bpagePublishedAt=(\d{1,16})\b")
    checked_raw = token(r"\bcheckedAt=(\d{1,16})\b")
    origin_day = int(origin_day_raw) if origin_day_raw is not None else None
    event_minute = int(site_claim.group(2)) if site_claim else None
    published_at = int(published_raw) if published_raw is not None else None
    checked_at = int(checked_raw) if checked_raw is not None else None
    event_at = int(claim_at_raw) if claim_at_raw is not None else (
        (origin_day * 1440 + event_minute) * 60_000 - IST_OFFSET_MS
        if origin_day is not None and event_minute is not None else None)
    page_age = ((checked_at - published_at) // 1_000
                if checked_at is not None and published_at is not None else None)
    event_age = ((checked_at - event_at) // 1_000
                 if checked_at is not None and event_at is not None else None)
    try:
        run_date = (EPOCH_IST + dt.timedelta(days=origin_day)).date().isoformat() \
            if origin_day is not None else None
        claim_time = _ist_time(event_at) if event_at is not None else None
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
    common_provenance = (
        train == EXPECTED_TRAIN and origin_day is not None and origin_day >= 0 and
        run_date is not None and site_claim is not None and published_at is not None and
        checked_at is not None and claim_time is not None and page_age is not None and
        0 <= page_age <= 300 and event_at is not None and event_age is not None and
        event_age >= 0 and event_at <= published_at <= checked_at
    )
    mapped_site_report = (
        identity == "DATE_ROUTE_MATCHED" and parsed == "SITE_COMPARISON" and
        site_date_matched and site_page_run_date == run_date and
        rows is not None and int(rows) > 0 and
        detached_rows is not None and int(detached_rows) == 0 and
        site_claim_kind in {"ARRIVAL", "DEPARTURE"} and
        site_route is not None and selected_route is not None and
        site_page_endpoints is not None and site_route == selected_route == site_page_endpoints and
        site_card_containers is not None and int(site_card_containers) > 0 and
        claim_time_basis == "selected_origin_date_verified"
    )
    detached_date_basis_matches = (
        identity == "LIVE_ROUTE_MATCH_DATE_VERIFIED" and site_date_matched and
        site_page_run_date == run_date and
        claim_time_basis == "selected_origin_date_verified;route_conflict"
    ) or (
        identity == "LIVE_ROUTE_MATCH_RUN_DATE_UNVERIFIED" and not site_date_matched and
        claim_time_basis == "page_clock_aligned;selected_origin_day_unverified"
    )
    detached_site_claim = (
        identity in {"LIVE_ROUTE_MATCH_RUN_DATE_UNVERIFIED", "LIVE_ROUTE_MATCH_DATE_VERIFIED"} and
        parsed == "DETACHED_SITE_EVENT_CLAIM" and detached_rows is not None and
        int(detached_rows) > 0 and site_claim_kind in {"ARRIVAL", "DEPARTURE"} and
        site_route is not None and selected_route is not None and site_route != selected_route and
        live_identity_route_match and detached_date_basis_matches and
        rows is not None and int(rows) == 0
    )
    provenance_complete = common_provenance and mapped_site_report
    accepted_mode = ("DATED_ROUTE_MATCHED_SITE_EVENT" if mapped_site_report else
                     "DETACHED_SITE_CLAIM_DIAGNOSTIC_ONLY" if detached_site_claim else "NONE")
    accepted = test_passed and site_answered and not refused and \
        transport == "NETWORK_ANSWER" and mapped_site_report and provenance_complete
    reason = ("WEBSITE_REFUSED_WIRE" if wire_refused else
              "WEBSITE_REFUSED_UI_DIARY_UNAVAILABLE" if ui_refused else
              "DEVICE_TEST_NOT_EXECUTED" if not instrumentation else
              "SITE_ROUTE_MISMATCH_NOT_ACCEPTED" if site_answered and detached_site_claim else
              "SITE_RUN_DATE_MISMATCH" if site_answered and site_page_run_date not in (None, "unknown") and
                  run_date is not None and site_page_run_date != run_date else
              "SITE_RUN_DATE_UNVERIFIED" if site_answered and rows is not None and int(rows) > 0 and not site_date_matched else
              "PASS_DATED_ROUTE_MATCHED_SITE_EVENT" if accepted else
              "SITE_REPLY_OR_SOURCE_QUALIFICATION_MISSING" if site_answered else
              "NO_WEBSITE_NETWORK_ANSWER")
    if detached_site_claim and site_date_matched:
        run_date_basis = "explicit_page_date_matches_selected_origin_date; route conflict prevents acceptance"
    elif detached_site_claim:
        run_date_basis = (
            "selected_origin_day_unverified; site has no matching run-date label; "
            "claim clock aligned to fresh page publication")
    elif site_page_run_date not in (None, "unknown") and site_page_run_date != run_date:
        run_date_basis = "explicit_page_date_does_not_match_selected_origin_date"
    elif site_date_matched and site_page_run_date == run_date:
        run_date_basis = "explicit_page_date_matches_selected_origin_date"
    else:
        run_date_basis = "selected_origin_day_unverified; page has no matching explicit run date"
    return {"verdict": reason, "accepted": accepted, "instrumentationPassed": test_passed,
            "websiteGetCount": len(attempted) if attempted else None, "websiteAnswered": site_answered,
            "websiteRefused": refused, "wireReceiptAvailable": bool(attempted),
            "source": HOST, "sourceTransport": transport or "unknown",
            "acceptedMode": accepted_mode,
            "siteRows": int(rows) if rows else 0,
            "detachedSiteClaims": int(detached_rows) if detached_rows else 0,
            "trainNumber": train or "unknown", "originDay": origin_day,
            "originRunDateIst": run_date,
            "runDateBasis": run_date_basis,
            "runIdentity": identity or "unknown",
            "siteRoute": site_route, "sitePageEndpoints": site_page_endpoints,
            "sitePageLabels": site_page_labels, "selectedRoute": selected_route,
            "siteCardContainers": int(site_card_containers) if site_card_containers else None,
            "siteDateMatched": site_date_matched, "sitePageRunDate": site_page_run_date or "unknown",
            "siteShape": site_shape,
            "liveIdentityRouteMatch": live_identity_route_match,
            "siteClaimStation": site_claim.group(1) if site_claim else "unknown",
            "siteClaimKind": site_claim_kind or "unknown",
            "siteClaimMinuteFromOrigin": event_minute,
            "siteClaimTimeBasis": claim_time_basis or "selected_origin_timetable",
            "siteClaimTimeIst": claim_time,
            "siteClaimAgeSecondsAtCheck": event_age,
            "pagePublishedAtIst": _ist_time(published_at),
            "pageAgeSecondsAtCheck": page_age,
            "checkedAtIst": _ist_time(checked_at),
            "provenanceComplete": provenance_complete,
            "googlePhoneAppFeed": False, "browserEngineRequest": True,
            "websiteGetCountScope": "main_document_navigation_only",
            "note": "A site event must be distinct from its printed timetable and match the selected train, route and explicit origin date; "
                    "it remains a website claim, not measured GPS or independent movement proof; "
                    "emulator WebView, not owner's phone or Google WIMT app"}


def self_test() -> None:
    published = int((dt.datetime(2026, 11, 12, 21, 5, tzinfo=IST) - EPOCH_UTC)
                    .total_seconds() * 1_000)
    checked = published + 30_000
    good = ("11-05 [HTTP] GET whereismytrain.org.in try#1 -> answered 108395B\n"
            "11-05 [EVIDENCE] source=whereismytrain.org.in (website) train=16227 "
            "originDay=20769 transport=NETWORK_ANSWER bytes=108395 sha256_16=0123456789abcdef "
            "parsed=SITE_COMPARISON runIdentity=DATE_ROUTE_MATCHED primaryActuals=0 primaryMarker=none "
            "siteRows=10 detachedSiteClaims=0 siteClaim=RRB:1217 siteClaimKind=ARRIVAL "
            "siteRoute=MYS>TLGP sitePageEndpoints=MYS>TLGP sitePageLabels=Mysuru_Junction>Talguppa "
            "selectedRoute=MYS>TLGP siteCardContainers=30 siteDateMatched=true "
            "sitePageRunDate=2026-11-12 siteShape=chars:108395~truncated:false~roots:10~cards:10~heads:h1_train_status "
            "siteClaimTimeBasis=selected_origin_date_verified "
            "pagePublishedAt=" + str(published) +
            " checkedAt=" + str(checked) + "\n")
    result = verdict("OK (1 test)", good, 0)
    assert result["accepted"]
    assert result["sitePageEndpoints"] == "MYS>TLGP"
    assert result["sitePageLabels"] == "Mysuru_Junction>Talguppa"
    assert result["selectedRoute"] == "MYS>TLGP"
    assert result["siteCardContainers"] == 30
    assert result["originRunDateIst"] == "2026-11-12"
    assert result["siteClaimTimeIst"] == "2026-11-12T20:17:00+05:30"
    assert result["siteClaimAgeSecondsAtCheck"] == 2_910
    assert result["pageAgeSecondsAtCheck"] == 30
    assert result["siteDateMatched"] is True
    assert result["sitePageRunDate"] == result["originRunDateIst"]
    assert result["siteShape"].startswith("chars:")
    undated = good.replace("runIdentity=DATE_ROUTE_MATCHED", "runIdentity=INFERRED") \
        .replace("siteDateMatched=true", "siteDateMatched=false") \
        .replace("sitePageRunDate=2026-11-12", "sitePageRunDate=unknown") \
        .replace("siteClaimTimeBasis=selected_origin_date_verified", "siteClaimTimeBasis=selected_origin_date_unverified")
    undated_result = verdict("OK (1 test)", undated, 0)
    assert not undated_result["accepted"]
    assert undated_result["verdict"] == "SITE_RUN_DATE_UNVERIFIED"
    mismatched_date = good.replace("sitePageRunDate=2026-11-12", "sitePageRunDate=2026-11-13")
    mismatched_result = verdict("OK (1 test)", mismatched_date, 0)
    assert not mismatched_result["accepted"]
    assert mismatched_result["verdict"] == "SITE_RUN_DATE_MISMATCH"
    assert not verdict("OK (1 test)", good.replace("siteRoute=MYS>TLGP", "siteRoute=SBC>SMET"), 0)["accepted"]
    assert not verdict("OK (1 test)", good.replace("siteRows=10", "siteRows=0"), 0)["accepted"]
    assert not verdict("OK (1 test)", good.replace("detachedSiteClaims=0",
        "detachedSiteClaims=1"), 0)["accepted"]
    detached_event = int((dt.datetime(2026, 11, 12, 21, 0, tzinfo=IST) - EPOCH_UTC)
                         .total_seconds() * 1_000)
    detached = ("11-05 [HTTP] GET whereismytrain.org.in try#1 -> answered 108395B\n"
        "11-05 [EVIDENCE] source=whereismytrain.org.in (website) train=16227 "
        "originDay=20769 transport=NETWORK_ANSWER parsed=DETACHED_SITE_EVENT_CLAIM "
        "runIdentity=LIVE_ROUTE_MATCH_RUN_DATE_UNVERIFIED primaryActuals=0 primaryMarker=none "
        "siteRows=0 detachedSiteClaims=1 siteClaimStation=ARU siteClaim=ARU:1260 "
        "siteClaimKind=ARRIVAL siteRoute=MYS>TLGP selectedRoute=SBC>SMET "
        "liveIdentityRouteMatch=true "
        "siteClaimTimeBasis=page_clock_aligned;selected_origin_day_unverified "
        "siteClaimAt=" + str(detached_event) + " pagePublishedAt=" + str(published) +
        " checkedAt=" + str(checked) + "\n")
    detached_result = verdict("OK (1 test)", detached, 0)
    assert not detached_result["accepted"]
    assert detached_result["verdict"] == "SITE_ROUTE_MISMATCH_NOT_ACCEPTED"
    assert detached_result["acceptedMode"] == "DETACHED_SITE_CLAIM_DIAGNOSTIC_ONLY"
    assert detached_result["siteRows"] == 0 and detached_result["detachedSiteClaims"] == 1
    assert detached_result["provenanceComplete"] is False
    assert detached_result["runDateBasis"].startswith("selected_origin_day_unverified")
    assert detached_result["siteClaimTimeIst"] == "2026-11-12T21:00:00+05:30"
    dated_detached = detached.replace("runIdentity=LIVE_ROUTE_MATCH_RUN_DATE_UNVERIFIED",
        "runIdentity=LIVE_ROUTE_MATCH_DATE_VERIFIED").replace(
        "siteRoute=MYS>TLGP selectedRoute=SBC>SMET",
        "siteRoute=MYS>TLGP sitePageEndpoints=MYS>TLGP selectedRoute=SBC>SMET "
        "siteCardContainers=3 siteDateMatched=true sitePageRunDate=2026-11-12 "
        "siteShape=chars:100~roots:3~cards:3").replace(
        "siteClaimTimeBasis=page_clock_aligned;selected_origin_day_unverified",
        "siteClaimTimeBasis=selected_origin_date_verified;route_conflict")
    dated_detached_result = verdict("OK (1 test)", dated_detached, 0)
    assert not dated_detached_result["accepted"]
    assert dated_detached_result["verdict"] == "SITE_ROUTE_MISMATCH_NOT_ACCEPTED"
    assert dated_detached_result["acceptedMode"] == "DETACHED_SITE_CLAIM_DIAGNOSTIC_ONLY"
    assert dated_detached_result["siteDateMatched"] is True
    assert dated_detached_result["runDateBasis"].startswith(
        "explicit_page_date_matches_selected_origin_date; route conflict")
    assert not verdict("OK (1 test)", detached.replace("siteRows=0", "siteRows=1"), 0)["accepted"]
    assert not verdict("OK (1 test)", detached.replace("liveIdentityRouteMatch=true",
        "liveIdentityRouteMatch=false"), 0)["accepted"]
    assert not verdict("OK (1 test)", detached.replace("selectedRoute=SBC>SMET",
        "selectedRoute=MYS>TLGP"), 0)["accepted"]
    assert not verdict("OK (1 test)", detached.replace("detachedSiteClaims=1",
        "detachedSiteClaims=0"), 0)["accepted"]
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
