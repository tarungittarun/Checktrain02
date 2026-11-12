#!/usr/bin/env python3
"""Offline tests for the WIMT multi-train report's strict acceptance boundaries."""
import json
import unittest

from wimt_live_comparison_report import PASS_LINE, _valid_attempt, make_report


def _capture_manifest(train):
    return [
        {"role": role, "name": f"wimt-compare-{train}-{suffix}.png",
         "bytes": 24_000, "dimensions": [1080, 2400]}
        for role, suffix in (("appLive", "app-live"),
                             ("appDetails", "app-details"),
                             ("webCache", "web-cache"),
                             ("webDetails", "web-details"))
    ]


class WimtLiveComparisonReportTest(unittest.TestCase):
    def setUp(self):
        self.attempts = [_valid_attempt(n) for n in ("16315", "12951", "12301")]
        self.device_log = "\n".join(
            "I WimtMultiTrain: WIMT_MULTI_ATTEMPT_JSON=" + json.dumps(row)
            for row in self.attempts
        ) + "\nI WimtMultiTrain: " + PASS_LINE
        self.screens = json.dumps({
            "verified": True,
            "appPackage": "in.opentrack.india",
            "captureSource": "fresh in-test UiAutomator screenshots and matching app-window hierarchies",
            "comparisons": [
                {
                    "trainNo": row["trainNo"],
                    "serviceDate": row["serviceDate"],
                    "overlappingEventFields": row["overlappingEventFields"],
                    "matchedEventFields": row["matchedEventFields"],
                    "mismatchFields": row["mismatchFields"],
                    "appOnlyEventFields": row["appOnlyEventFields"],
                    "webOnlyEventFields": row["webOnlyEventFields"],
                    "screenshots": _capture_manifest(row["trainNo"]),
                }
                for row in self.attempts
            ],
        })

    def test_fresh_identified_comparisons_with_mismatch_are_reported_not_called_parity(self):
        report = make_report("OK (1 test)", self.device_log, 0, self.screens)
        self.assertTrue(report["accepted"])
        self.assertFalse(report["parityVerified"])
        self.assertEqual(3, report["completedComparisons"])
        self.assertGreater(report["eventMismatchCount"], 0)

    def test_identical_event_fields_are_not_mislabeled_independent_parity(self):
        attempts = json.loads(json.dumps(self.attempts))
        for row in attempts:
            row["mismatchFields"] = []
            row["matchedEventFields"] = row["overlappingEventFields"]
        log = "\n".join(
            "WIMT_MULTI_ATTEMPT_JSON=" + json.dumps(row) for row in attempts
        ) + "\n" + PASS_LINE
        screens = json.loads(self.screens)
        for row in screens["comparisons"]:
            row["mismatchFields"] = []
            row["matchedEventFields"] = row["overlappingEventFields"]
        report = make_report("OK (1 test)", log, 0, json.dumps(screens))
        self.assertTrue(report["accepted"])
        self.assertTrue(report["eventValuesMatch"])
        self.assertFalse(report["parityVerified"])

    def test_stale_or_wrong_date_rows_do_not_count(self):
        stale = json.loads(json.dumps(self.attempts))
        stale[0]["webFresh"] = False
        stale[1]["webServiceDate"] = "11 Oct 2026"
        log = "\n".join(
            "WIMT_MULTI_ATTEMPT_JSON=" + json.dumps(row) for row in stale
        ) + "\n" + PASS_LINE
        report = make_report("OK (1 test)", log, 0, self.screens)
        self.assertFalse(report["accepted"])
        self.assertEqual(1, report["completedComparisons"])

    def test_old_row_update_and_wrong_train_identity_do_not_count(self):
        attempts = json.loads(json.dumps(self.attempts))
        attempts[0]["webFreshestRowAgeSeconds"] = 601
        attempts[1]["webRequestTrainNumber"] = "99999"
        log = "\n".join(
            "WIMT_MULTI_ATTEMPT_JSON=" + json.dumps(row) for row in attempts
        ) + "\n" + PASS_LINE
        report = make_report("OK (1 test)", log, 0, self.screens)
        self.assertFalse(report["accepted"])
        self.assertEqual(1, report["completedComparisons"])

    def test_row_age_must_reconcile_to_captured_update_and_check_times(self):
        attempts = json.loads(json.dumps(self.attempts))
        attempts[0]["webFreshestRowAgeSeconds"] = 41
        log = "\n".join(
            "WIMT_MULTI_ATTEMPT_JSON=" + json.dumps(row) for row in attempts
        ) + "\n" + PASS_LINE
        report = make_report("OK (1 test)", log, 0, self.screens)
        self.assertFalse(report["accepted"])
        self.assertEqual(2, report["completedComparisons"])

    def test_zero_completed_comparisons_is_not_misreported_as_screenshot_failure(self):
        stale = _valid_attempt("16315")
        stale.update(verdict="WEB_CACHE_SOURCE_NOT_QUALIFIED", webFresh=False,
                     webFreshestRowAgeSeconds=2_658,
                     webFreshestRowUpdatedAt="12 Oct 2026 09:37:22 IST",
                     webFreshnessCheckedAt="12 Oct 2026 10:21:40.000 IST")
        log = "WIMT_MULTI_ATTEMPT_JSON=" + json.dumps(stale)
        screens = json.dumps({
            "verified": False,
            "verificationStatus": "NO_COMPLETED_COMPARISONS_TO_VERIFY",
            "acceptedComparisonCount": 0,
            "comparisons": [],
            "diagnosticOnlyAttempts": [{
                "trainNo": "16315", "countedTowardAcceptance": False,
                "listedCaptureCount": 5, "validPngAndAppHierarchyCount": 5,
            }],
        })
        report = make_report("FAILURES!!!", log, 1, screens)
        self.assertFalse(report["accepted"])
        self.assertEqual("INSUFFICIENT_FRESH_SAME_DATE_COMPARISONS", report["verdict"])
        self.assertFalse(report["inAppScreensVerified"])
        self.assertIn("WEB_CACHE_SOURCE_NOT_QUALIFIED=1", report["reason"])
        self.assertEqual(5, report["screenshotVerification"]["diagnosticOnlyAttempts"][0]["validPngAndAppHierarchyCount"])

    def test_comparison_without_verified_saved_default_does_not_count(self):
        attempts = json.loads(json.dumps(self.attempts))
        attempts[0]["defaultPreferenceRestored"] = False
        attempts[0]["defaultResetSucceeded"] = False
        log = "\n".join(
            "WIMT_MULTI_ATTEMPT_JSON=" + json.dumps(row) for row in attempts
        ) + "\n" + PASS_LINE
        report = make_report("OK (1 test)", log, 0, self.screens)
        self.assertFalse(report["accepted"])
        self.assertEqual(2, report["completedComparisons"])

    def test_missing_capture_or_test_verdict_never_passes(self):
        report = make_report("FAILURES!!!", self.device_log, 1, "{}")
        self.assertFalse(report["accepted"])
        self.assertFalse(report["inAppScreensVerified"])
        self.assertEqual("GENUINE_COMPARISON_SCREENSHOTS_NOT_VERIFIED", report["verdict"])

    def test_empty_capture_manifest_is_not_reported_as_verified(self):
        screens = json.loads(self.screens)
        screens["comparisons"] = []
        screens["acceptedComparisonCount"] = 0
        report = make_report("OK (1 test)", self.device_log, 0, json.dumps(screens))
        self.assertFalse(report["accepted"])
        self.assertFalse(report["inAppScreensVerified"])
        self.assertEqual("GENUINE_COMPARISON_SCREENSHOTS_NOT_VERIFIED", report["verdict"])

    def test_capture_result_conflict_fails_closed(self):
        screens = json.loads(self.screens)
        screens["comparisons"][0]["mismatchFields"] = []
        report = make_report("OK (1 test)", self.device_log, 0, json.dumps(screens))
        self.assertFalse(report["accepted"])
        self.assertEqual("CAPTURE_OR_RESULT_CONFLICT", report["verdict"])

    def test_refusal_is_preserved_and_not_retried_as_acceptance(self):
        refused = _valid_attempt("16315")
        refused.update(verdict="DIRECT_HTTP_403_REFUSED", directHttpStatus=403)
        log = "WIMT_MULTI_ATTEMPT_JSON=" + json.dumps(refused)
        report = make_report("FAILURES!!!", log, 1, "{}")
        self.assertFalse(report["accepted"])
        self.assertEqual("LIVE_SOURCE_REFUSED_OR_RATE_LIMITED", report["verdict"])


if __name__ == "__main__":
    unittest.main()
