#!/usr/bin/env python3
"""Offline report-gate tests. Synthetic fixtures live only in a temporary directory."""
from __future__ import annotations

import struct
import tempfile
import unittest
import zlib
from pathlib import Path

from wimt_live_api_46_minute_report import build_report


def _detail(minute: int | str, *, current_station: str = "YPR", fresh: bool = True) -> str:
    cycle = "final" if minute == "final" else str(minute)
    return (
        f"WIMT_SOAK_DETAIL sample={int(minute) + 1 if isinstance(minute, int) else 11} "
        f"cycle={cycle} elapsedMinutes={minute if isinstance(minute, int) else 46} "
        "httpStatus=200 runDateMatched=true routeMatched=true "
        f"fresh={str(fresh).lower()} stale=false freshnessAccurate=true "
        "rows=5 cells=3 selected=true disclosed=true "
        f"currentStationClaim={current_station}"
    )


def _png_chunk(kind: bytes, payload: bytes) -> bytes:
    return (struct.pack(">I", len(payload)) + kind + payload +
            struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))


def _png_bytes() -> bytes:
    signature = b"\x89PNG\r\n\x1a\n"
    header = struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)
    pixel = zlib.compress(b"\x00\xff\x00\x00\xff")
    metadata = b"Description\x00" + b"x" * 1_100
    return (signature + _png_chunk(b"IHDR", header) + _png_chunk(b"tEXt", metadata) +
            _png_chunk(b"IDAT", pixel) + _png_chunk(b"IEND", b""))


def _fixture(root: Path, *, drop_minute: int | None = None,
             bad_station_minute: int | None = None, missing_screen: str | None = None,
             instrumentation_pass: bool = True) -> None:
    lines = [
        "WIMT_SOAK_TRAIN trainNo=20651 runDate=12-10-2026 route=SBC_→_TLGP "
        "selection=IST-hour-schedule-candidate currentStationRequired=true",
        "WIMT_SOAK_INITIAL httpStatus=200 responseBytes=512 runDateMatched=true "
        "routeMatched=true fresh=true rows=5 cells=3 responseClass=HTTP_200_SOURCE_ROWS_PRESENT "
        "parserOutcome=SOURCE_ROWS_PRESENT selected=true disclosed=true "
        "currentStationClaim=YPR accepted=true",
        "WIMT_SOAK_AUTO_ENABLE revealed=true initialChecked=false resetTap=not-needed "
        "checkedAfterReset=false enableTap=true enableRetryTap=not-attempted "
        "checkedAfterEnable=true enabled=true",
        "WIMT_SOAK_STARTED durationMinutes=46 autoIntervalMinutes=1 "
        "requireCurrentStation=true requiredReceipts=46 initialHttpStatus=200",
    ]
    for minute in range(47):
        if minute == drop_minute:
            continue
        lines.append(
            f"WIMT_SOAK_SAMPLE minute={minute} elapsedSeconds={minute * 60} "
            f"receiptClock=12:{minute:02d}:00 selected=true visibleCells=3 "
            "sourceRowsVisible=true autoEnabled=true receiptFailure=false"
        )
    for minute in range(5, 46, 5):
        station = "unknown" if minute == bad_station_minute else "YPR"
        lines.append(_detail(minute, current_station=station,
                             fresh=minute != bad_station_minute))
    lines.append(_detail("final"))
    lines.append(
        "WIMT_SOAK_SUMMARY durationMs=2760000 cycles=46 requiredReceipts=46 "
        "missedReceipts=0 detailSamples=11 freshSamples=11 nonFreshSamples=0 "
        "unqualifiedSamples=0 httpFailures=0 selectedLosses=0 "
        "autoStoppedAfterSourceFailure=false sourceFailureReason=none fullDuration=true "
        "finalSelected=true autoOff=true currentStationRequired=true "
        "currentStationChecksPassed=true"
    )
    (root / "soak-trace.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (root / "instrumentation.txt").write_text(
        "OK (1 test)\n" if instrumentation_pass else "FAILURES!!!\n", encoding="utf-8")
    (root / "runner-exit.txt").write_text("instrumentationExitCode=0\n" if instrumentation_pass
                                           else "instrumentationExitCode=1\n", encoding="utf-8")
    screens = root / "screens"
    screens.mkdir()
    png = _png_bytes()
    for stem in ("wimt-soak-start", "wimt-soak-15m", "wimt-soak-30m",
                 "wimt-soak-45m", "wimt-soak-46m"):
        if stem == missing_screen:
            continue
        (screens / f"{stem}.png").write_bytes(png)
        (screens / f"{stem}.xml").write_text(
            '<hierarchy package="in.opentrack.india">' + "x" * 120 + "</hierarchy>",
            encoding="utf-8")


class Wimt46ReportTests(unittest.TestCase):
    def test_only_complete_46_minute_current_station_and_installed_app_evidence_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            evidence = Path(tmp)
            _fixture(evidence)
            report = build_report(evidence)
            self.assertTrue(report["passed"], report["checks"])
            self.assertEqual("PASS_46_MINUTE_WIMT_IN_APP_STATUS_OBSERVATION", report["verdict"])
            self.assertEqual("20651", report["trainNo"])
            self.assertEqual(46, report["refresh"]["receipts"])
            self.assertEqual(10, len(report["currentStationCheckpoints"]))
            self.assertFalse(report["independentlyVerifiedMovement"])

    def test_missing_minute_receipt_never_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            evidence = Path(tmp)
            _fixture(evidence, drop_minute=22)
            report = build_report(evidence)
            self.assertFalse(report["passed"])
            self.assertFalse(report["checks"]["minuteZeroThrough46SamplesPresent"])

    def test_missing_or_stale_current_station_claim_never_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            evidence = Path(tmp)
            _fixture(evidence, bad_station_minute=20)
            report = build_report(evidence)
            self.assertFalse(report["passed"])
            self.assertFalse(report["checks"]["freshCurrentStationClaimAtStartAndCheckpoints"])

    def test_missing_real_app_hierarchy_never_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            evidence = Path(tmp)
            _fixture(evidence, missing_screen="wimt-soak-30m")
            report = build_report(evidence)
            self.assertFalse(report["passed"])
            self.assertFalse(report["checks"]["genuineAppScreenshotsAndHierarchiesAtRequiredMilestones"])

    def test_instrumentation_failure_never_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            evidence = Path(tmp)
            _fixture(evidence, instrumentation_pass=False)
            report = build_report(evidence)
            self.assertFalse(report["passed"])
            self.assertFalse(report["checks"]["instrumentationPassed"])


if __name__ == "__main__":
    unittest.main()
