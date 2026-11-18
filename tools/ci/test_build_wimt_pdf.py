#!/usr/bin/env python3
"""Regression tests for the strict PASS label versus actual screenshot diagnostics."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_wimt_pdf import accepted_result


class PdfVerdictTest(unittest.TestCase):
    def setUp(self):
        self.good = {
            "accepted": True,
            "instrumentationPassed": True,
            "websiteAnswered": True,
            "websiteRefused": False,
            "wireReceiptAvailable": True,
            "websiteGetCount": 1,
            "source": "whereismytrain.org.in",
            "trainNumber": "16227",
            "originDay": 20769,
            "originRunDateIst": "2026-11-12",
            "siteRows": 4,
            "acceptedMode": "DATED_ROUTE_MATCHED_SITE_EVENT",
            "runIdentity": "DATE_ROUTE_MATCHED",
            "siteDateMatched": True,
            "sitePageRunDate": "2026-11-12",
            "siteRoute": "MYS>TLGP",
            "selectedRoute": "MYS>TLGP",
            "sitePageEndpoints": "MYS>TLGP",
            "siteClaimTimeBasis": "selected_origin_date_verified",
            "siteClaimKind": "ARRIVAL",
            "siteClaimStation": "RRB",
            "siteClaimTimeIst": "2026-11-12T20:17:00+05:30",
            "siteClaimAgeSecondsAtCheck": 2910,
            "pagePublishedAtIst": "2026-11-12T21:05:00+05:30",
            "pageAgeSecondsAtCheck": 30,
            "checkedAtIst": "2026-11-12T21:05:30+05:30",
            "siteCardContainers": 8,
            "detachedSiteClaims": 0,
            "provenanceComplete": True,
            "sourceTransport": "NETWORK_ANSWER",
            "googlePhoneAppFeed": False,
            "browserEngineRequest": True,
            "websiteGetCountScope": "main_document_navigation_only",
        }

    def test_only_explicit_same_train_route_date_event_is_pass(self):
        self.assertTrue(accepted_result(self.good))

    def test_unqualified_http_answer_is_never_pass(self):
        cases = [
            {"accepted": False},
            {"instrumentationPassed": False},
            {"websiteAnswered": False},
            {"websiteRefused": True},
            {"wireReceiptAvailable": False},
            {"websiteGetCount": 0},
            {"trainNumber": "12627"},
            {"originDay": True},
            {"originRunDateIst": "not-a-date"},
            {"siteRows": 0},
            {"acceptedMode": "ROUTE_MATCHED_SITE_REPORT"},
            {"runIdentity": "INFERRED"},
            {"siteDateMatched": False},
            {"originRunDateIst": None},
            {"sitePageRunDate": "2026-11-13"},
            {"siteClaimKind": "UNKNOWN"},
            {"siteClaimStation": "unknown"},
            {"pageAgeSecondsAtCheck": 301},
            {"googlePhoneAppFeed": True},
            {"browserEngineRequest": False},
            {"websiteGetCountScope": "subresources"},
            {"siteRoute": "SBC>SMET"},
            {"sitePageEndpoints": "SBC>SMET"},
            {"siteClaimTimeBasis": "page_clock_aligned;selected_origin_day_unverified"},
            {"siteCardContainers": 0},
            {"detachedSiteClaims": 1},
            {"provenanceComplete": False},
            {"sourceTransport": "CACHED_BODY"},
        ]
        for change in cases:
            with self.subTest(change=change):
                self.assertFalse(accepted_result(self.good | change))

    def test_a_failed_live_fetch_can_still_be_archived_as_diagnostic(self):
        diagnostic = self.good | {
            "accepted": False,
            "runIdentity": "INFERRED",
            "siteDateMatched": False,
            "acceptedMode": "NONE",
            "siteRows": 0,
        }
        self.assertFalse(accepted_result(diagnostic))


if __name__ == "__main__":
    unittest.main()
