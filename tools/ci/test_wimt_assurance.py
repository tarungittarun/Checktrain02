#!/usr/bin/env python3
"""Offline negative cases; synthetic evidence NEVER represents a real website response."""
from pathlib import Path
import json
import tempfile
import unittest

from audit_wimt_assurance import audit
from verify_wimt_screens import NAMES, PNG


class AssuranceAuditTest(unittest.TestCase):
    def test_cannot_promote_a_mock_or_refusal_to_live_site_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            screens = folder / "screens"
            screens.mkdir()
            for name in NAMES:
                (screens / (name + ".png")).write_bytes(PNG + b"test-fixture" * 300)
                selected = ("Selected NTES · official" if name == NAMES[0]
                            else "Selected Where Is My Train · website")
                (screens / (name + ".xml")).write_text(
                    '<hierarchy><node package="in.opentrack.india" '
                    'resource-id="in.opentrack.india:id/trainNo" text="16227" />'
                    '<node package="in.opentrack.india" '
                    'resource-id="in.opentrack.india:id/routeLabel" text="MYS → TLGP" />'
                    '<node package="in.opentrack.india" '
                    'resource-id="in.opentrack.india:id/runDate" text="run of 12 Nov 2026" />'
                    '<node package="in.opentrack.india" '
                    'resource-id="in.opentrack.india:id/sourceSpinner" '
                    f'content-desc="{selected}" /></hierarchy>')
            sample = {"accepted": True, "instrumentationPassed": True,
                      "provenanceComplete": True,
                      "acceptedMode": "DATED_ROUTE_MATCHED_SITE_EVENT",
                      "runIdentity": "DATE_ROUTE_MATCHED", "siteDateMatched": True,
                      "originDay": 20769, "originRunDateIst": "2026-11-12",
                      "sitePageRunDate": "2026-11-12", "trainNumber": "16227",
                      "source": "whereismytrain.org.in",
                      "siteRoute": "MYS>TLGP", "selectedRoute": "MYS>TLGP",
                      "sitePageEndpoints": "MYS>TLGP",
                      "siteClaimTimeBasis": "selected_origin_date_verified",
                      "siteCardContainers": 8, "siteClaimKind": "ARRIVAL",
                      "siteClaimStation": "RRB",
                      "siteClaimTimeIst": "2026-11-12T20:17:00+05:30",
                      "siteClaimAgeSecondsAtCheck": 2910,
                      "pagePublishedAtIst": "2026-11-12T21:05:00+05:30",
                      "pageAgeSecondsAtCheck": 30,
                      "checkedAtIst": "2026-11-12T21:05:30+05:30",
                      "detachedSiteClaims": 0,
                      "websiteAnswered": True, "wireReceiptAvailable": True,
                      "websiteGetCount": 1, "websiteRefused": False,
                      "sourceTransport": "NETWORK_ANSWER", "siteRows": 2,
                      "googlePhoneAppFeed": False, "browserEngineRequest": True,
                      "websiteGetCountScope": "main_document_navigation_only"}
            (folder / "instrumentation.txt").write_text("OK (1 test)")
            (folder / "screen-verification.json").write_text(json.dumps({
                "displayedRefusalRequired": False,
                "selectedWebsiteRequired": True,
                "sameSelectedOriginDate": True,
                "verifiedAppScreens": {name: {} for name in NAMES}}))
            result = folder / "result.json"
            result.write_text(json.dumps(sample))
            self.assertEqual(audit(folder)["sourceVerdict"],
                             "DATED_ROUTE_MATCHED_SITE_EVENT_AND_APP_UI_ACCEPTED")
            for key, value in (("accepted", False), ("websiteGetCount", None),
                               ("wireReceiptAvailable", False), ("siteRows", 0),
                               ("siteDateMatched", False), ("sitePageRunDate", "2026-11-13"),
                               ("trainNumber", "12627"), ("originDay", 20768),
                               ("originRunDateIst", "not-a-date"),
                               ("runIdentity", "INFERRED"),
                               ("siteRoute", "SBC>SMET"), ("sitePageEndpoints", "SBC>SMET"),
                               ("siteClaimTimeBasis", "page_clock_aligned;selected_origin_day_unverified"),
                               ("siteCardContainers", 0), ("detachedSiteClaims", 1),
                               ("websiteRefused", True), ("sourceTransport", "unknown")):
                with self.subTest(key=key):
                    result.write_text(json.dumps({**sample, key: value}))
                    with self.assertRaises(ValueError):
                        audit(folder)
            result.write_text(json.dumps(sample))
            (folder / "instrumentation.txt").write_text("FAILURES!!!")
            with self.assertRaises(ValueError):
                audit(folder)
            (folder / "instrumentation.txt").write_text("OK (1 test)")
            (screens / (NAMES[1] + ".xml")).write_text(
                '<hierarchy><node package="com.google.android.apps.nexuslauncher" /></hierarchy>')
            with self.assertRaises(ValueError):
                audit(folder)

    def test_detached_claim_never_counts_as_selected_run_acceptance(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            screens = folder / "screens"
            screens.mkdir()
            for name in NAMES:
                (screens / (name + ".png")).write_bytes(PNG + b"test-fixture" * 300)
                selected = ("Selected NTES · official" if name == NAMES[0]
                            else "Selected Where Is My Train · website")
                (screens / (name + ".xml")).write_text(
                    '<hierarchy><node package="in.opentrack.india" '
                    'resource-id="in.opentrack.india:id/trainNo" text="16227" />'
                    '<node package="in.opentrack.india" '
                    'resource-id="in.opentrack.india:id/routeLabel" text="MYS → TLGP" />'
                    '<node package="in.opentrack.india" '
                    'resource-id="in.opentrack.india:id/runDate" text="run of 12 Nov 2026" />'
                    '<node package="in.opentrack.india" '
                    'resource-id="in.opentrack.india:id/sourceSpinner" '
                    f'content-desc="{selected}" /></hierarchy>')
            sample = {"accepted": True, "instrumentationPassed": True,
                      "provenanceComplete": True,
                      "acceptedMode": "DETACHED_SITE_CLAIM_DIAGNOSTIC_ONLY",
                      "runIdentity": "LIVE_ROUTE_MATCH_DATE_VERIFIED",
                      "siteDateMatched": True, "originDay": 20769,
                      "originRunDateIst": "2026-11-12",
                      "sitePageRunDate": "2026-11-12", "trainNumber": "16227",
                      "source": "whereismytrain.org.in", "websiteAnswered": True,
                      "wireReceiptAvailable": True, "websiteGetCount": 1,
                      "websiteRefused": False, "sourceTransport": "NETWORK_ANSWER",
                      "siteRows": 0, "detachedSiteClaims": 1, "siteClaimKind": "ARRIVAL",
                      "siteClaimStation": "ARU",
                      "siteClaimTimeIst": "2026-11-12T21:00:00+05:30",
                      "siteClaimAgeSecondsAtCheck": 330,
                      "pagePublishedAtIst": "2026-11-12T21:05:00+05:30",
                      "pageAgeSecondsAtCheck": 30,
                      "checkedAtIst": "2026-11-12T21:05:30+05:30",
                      "siteClaimTimeBasis": "selected_origin_date_verified;route_conflict",
                      "siteCardContainers": 3,
                      "liveIdentityRouteMatch": True, "siteRoute": "MYS>TLGP",
                      "sitePageEndpoints": "MYS>TLGP", "selectedRoute": "SBC>SMET",
                      "googlePhoneAppFeed": False, "browserEngineRequest": True,
                      "websiteGetCountScope": "main_document_navigation_only"}
            (folder / "instrumentation.txt").write_text("OK (1 test)")
            (folder / "screen-verification.json").write_text(json.dumps({
                "displayedRefusalRequired": False,
                "selectedWebsiteRequired": True,
                "sameSelectedOriginDate": True,
                "verifiedAppScreens": {name: {} for name in NAMES}}))
            result = folder / "result.json"
            result.write_text(json.dumps(sample))
            with self.assertRaisesRegex(ValueError, "not a source-strict"):
                audit(folder)
            for key, value in (("liveIdentityRouteMatch", False),
                               ("siteRoute", "SBC>SMET"),
                               ("detachedSiteClaims", 0),
                               ("siteRows", 2),
                               ("provenanceComplete", False)):
                with self.subTest(key=key):
                    result.write_text(json.dumps({**sample, key: value}))
                    with self.assertRaises(ValueError):
                        audit(folder)
            # Contradictory rows cannot override a detached mode or route-conflicting date evidence.
            result.write_text(json.dumps({**sample, "siteRows": 2,
                                         "acceptedMode": "ROUTE_MATCHED_SITE_REPORT",
                                         "runIdentity": "INFERRED"}))
            with self.assertRaises(ValueError):
                audit(folder)


if __name__ == "__main__":
    unittest.main()
