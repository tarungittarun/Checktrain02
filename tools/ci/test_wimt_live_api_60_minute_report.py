#!/usr/bin/env python3
"""Synthetic, offline acceptance tests for the strict 60-minute WIMT report."""
from __future__ import annotations

import struct
import tempfile
import unittest
import zlib
from pathlib import Path

from wimt_live_api_60_minute_report import build_report

ROOT = Path(__file__).resolve().parents[2]


def _chunk(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)


def _png() -> bytes:
    header = struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)
    pixel = zlib.compress(b"\0\xff\0\0\xff")
    metadata = b"Description\0" + b"x" * 1100
    return (b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", header) + _chunk(b"tEXt", metadata) +
            _chunk(b"IDAT", pixel) + _chunk(b"IEND", b""))


def _detail(minute: int | str, *, fresh: bool = True, running: bool = True,
            event_changed: bool = False) -> str:
    cycle = "final" if minute == "final" else str(minute)
    elapsed = 60 if minute == "final" else int(minute)
    return (
        f"WIMT_SOAK_DETAIL sample=1 cycle={cycle} elapsedMinutes={elapsed} "
        "httpStatus=200 runDateMatched=true routeMatched=true "
        f"fresh={str(fresh).lower()} stale=false freshnessAccurate=true "
        "rows=5 cells=3 selected=true disclosed=true currentStationClaim=YPR latestEventAge=1_min_ago "
        f"wimtReportsRunning={str(running).lower()} "
        f"eventSnapshotChanged={str(event_changed).lower()}"
    )


def fixture(root: Path, *, missing_target: int | None = None, stale_target: int | None = None,
            bad_checkpoint: int | None = None, receipts: int = 60,
            unique_event_snapshots: int = 13) -> None:
    lines = [
        "WIMT_SOAK_TRAIN trainNo=20652 runDate=12-10-2026 route=SBC_→_TLGP selection=IST-hour-schedule-candidate currentStationRequired=true",
        "WIMT_SOAK_INITIAL httpStatus=200 responseBytes=512 runDateMatched=true routeMatched=true fresh=true freshnessAccurate=true rows=5 cells=3 selected=true disclosed=true currentStationClaim=YPR latestEventAge=1_min_ago wimtReportsRunning=true parserOutcome=SOURCE_ROWS_PRESENT accepted=true",
        "WIMT_SOAK_AUTO_ENABLE revealed=true initialChecked=false checkedAfterEnable=true enabled=true",
        "WIMT_SOAK_STARTED durationMinutes=60 autoIntervalMinutes=1 observationIntervalSeconds=30 expectedObservationSamples=121 requireCurrentStation=true runningStatusRequired=true requiredReceipts=60 initialGateAccepted=true autoStarted=true",
    ]
    for target in range(0, 3601, 30):
        if target == missing_target:
            continue
        summary_class = "STALE" if target == stale_target else "FRESH_LABEL"
        lines.append(
            f"WIMT_SOAK_OBSERVATION sample={target // 30} targetElapsedSeconds={target} elapsedSeconds={target} jitterMs=0 selected=true visibleCells=3 sourceRowsVisible=true summaryClass={summary_class} autoEnabled=true receiptClock=12:00:00 receiptClass=CHECKED trackingScreen=true appForeground=true processPssKb=100000 processCpuPercent=1.0"
        )
    for minute in range(5, 60, 5):
        lines.append(_detail(minute, fresh=minute != bad_checkpoint,
                             running=minute != bad_checkpoint, event_changed=minute == 5))
    lines.append(_detail("final"))
    lines.append(
        f"WIMT_SOAK_SUMMARY durationMs=3600000 cycles={receipts} requiredReceipts=60 missedReceipts=0 detailSamples=13 freshSamples=13 nonFreshSamples=0 unqualifiedSamples=0 httpFailures=0 selectedLosses=0 autoStoppedAfterSourceFailure=false sourceFailureReason=none fullDuration=true finalSelected=true autoOff=true currentStationRequired=true currentStationChecksPassed=true runningStatusRequired=true wimtRunningStatusChecksPassed=true initialGateAccepted=true autoStarted=true uniqueSourceEventSnapshots={unique_event_snapshots} observationIntervalSeconds=30 observationSamples={121 - (1 if missing_target is not None else 0)} expectedObservationSamples=121 missedObservationSlots=0 maxObservationJitterMs=0"
    )
    (root / "soak-trace.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (root / "instrumentation.txt").write_text("OK (1 test)\n", encoding="utf-8")
    (root / "runner-exit.txt").write_text("instrumentationExitCode=0\n", encoding="utf-8")
    screens = root / "screens"
    screens.mkdir()
    for stem in ("wimt-soak-start", "wimt-soak-15m", "wimt-soak-30m", "wimt-soak-45m", "wimt-soak-60m"):
        (screens / f"{stem}.png").write_bytes(_png())
        (screens / f"{stem}.xml").write_text('<hierarchy package="in.opentrack.india">' + "x" * 120 + "</hierarchy>", encoding="utf-8")


class Wimt60ReportTests(unittest.TestCase):
    def test_complete_60_minute_evidence_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixture(root)
            result = build_report(root)
            self.assertTrue(result["passed"], result["checks"])
            self.assertEqual("20652", result["trainNo"])
            self.assertEqual(121, result["uiObservationCount"])
            self.assertEqual(60, result["sourceRefreshReceipts"])
            self.assertEqual("SOURCE_ROWS_PRESENT", result["initialResponse"]["parserOutcome"])
            self.assertTrue(result["initialResponse"]["accepted"])
            self.assertEqual("1 min ago", result["initialResponse"]["latestEventAge"])
            self.assertEqual([5], result["eventUpdateCheckpoints"])
            self.assertEqual(13, result["uniqueSourceEventSnapshots"])
            self.assertFalse(result["independentlyVerifiedMovement"])


    def test_missing_30_second_slot_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixture(root, missing_target=150)
            result = build_report(root)
            self.assertFalse(result["passed"])
            self.assertFalse(result["checks"]["all121UiObservationsPresentAt30SecondTargets"])

    def test_stale_display_and_bad_five_minute_checkpoint_fail(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixture(root, stale_target=900, bad_checkpoint=20)
            result = build_report(root)
            self.assertFalse(result["passed"])
            self.assertFalse(result["checks"]["everyUiObservationShowsSelectedFreshSourceData"])
            self.assertFalse(result["checks"]["freshMatchedCurrentStationAndRunningGateAtFiveMinuteCheckpoints"])

    def test_missed_source_refresh_receipt_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixture(root, receipts=59)
            result = build_report(root)
            self.assertFalse(result["passed"])
            self.assertFalse(result["checks"]["60OneMinuteSourceReceiptsAndNoUnqualifiedResponses"])

    def test_fresh_stamps_without_changed_actual_event_rows_fail(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixture(root, unique_event_snapshots=1)
            result = build_report(root)
            self.assertFalse(result["passed"])
            self.assertFalse(result["checks"]["sourceActualEventRowsChangedAtLeastOnce"])

    def test_optional_train_override_is_omitted_when_blank(self) -> None:
        runner = (ROOT / "tools/ci/run_wimt_live_api_soak.sh").read_text(encoding="utf-8")
        self.assertIn('if [ -n "$SOAK_TRAIN_NO" ]; then', runner)
        self.assertIn('instrument_args+=(-e soak_train_no "$SOAK_TRAIN_NO")', runner)
        self.assertNotIn('  -e soak_train_no "$SOAK_TRAIN_NO" \\\n', runner)

    def test_unqualified_initial_source_is_reported_and_not_auto_polled(self) -> None:
        test = (ROOT / "app/src/androidTest/java/in/opentrack/india/e2e/WimtLiveApiSoakTest.kt")
        source = test.read_text(encoding="utf-8")
        gate = source.index('initial WIMT source response did not qualify; refusing to start Auto')
        receipts = source.index('fewer than $requiredReceipts one-minute selected-source receipts')
        self.assertLess(gate, receipts)
        self.assertIn('initialGateAccepted && autoStarted)', source)
        workflow = (ROOT / ".github/workflows/wimt-live-api-60-minute.yml").read_text(encoding="utf-8")
        self.assertGreaterEqual(workflow.count("soak-trace.txt"), 2)


if __name__ == "__main__":
    unittest.main()
