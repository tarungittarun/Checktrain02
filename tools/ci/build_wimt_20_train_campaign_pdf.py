#!/usr/bin/env python3
"""Create a detailed consolidated PDF and machine-readable summary for the 20-slot campaign."""
from __future__ import annotations

import argparse
import json
import struct
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from xml.sax.saxutils import escape

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.lib.utils import ImageReader
from reportlab.platypus import (
    Image, KeepTogether, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle,
)

PROVENANCE = (
    "Eligibility is based on the generated timetable's known run-day bitmap and scheduled window, "
    "corroborated by one direct WIMT .in response with matching run date, route endpoints, endpoint "
    "schedule clocks, and a fresh source update stamp. This is not independent railway verification, "
    "verified train movement, GPS, provider permission, or the Google WIMT Android app. The response "
    "may itself be server-cached. The app screen is tested with NTES · official selected; WIMT .org.in "
    "website claims, eRail, WIMT web cache, and the Google WIMT Android app are separate sources and "
    "are not merged or counted as live actuals."
)
AUTO_LIMIT = (
    "Auto was enabled on the visible tracking screen for the test interval and turned OFF at the end. "
    "This is not evidence of background monitoring. No arrival alert, scheduled reminder, or ringing "
    "control was set or exercised."
)


def read_json(path: Path, default: Any = None) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return default


def _minute(row: dict[str, Any]) -> int:
    try:
        return int(row.get("minute", -1))
    except (TypeError, ValueError, OverflowError):
        return -1


def read_observations(path: Path) -> list[dict[str, Any]]:
    rows = []
    try:
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            try:
                value = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict):
                rows.append(value)
    except OSError:
        pass
    rows.sort(key=_minute)
    return rows


def png_ok(path: Path) -> bool:
    try:
        with path.open("rb") as stream:
            header = stream.read(24)
        if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n":
            return False
        width, height = struct.unpack(">II", header[16:24])
        return width > 0 and height > 0 and path.stat().st_size > 1_000
    except OSError:
        return False


def _clean(value: Any, limit: int = 400) -> str:
    if value is None:
        return ""
    return str(value).replace("\x00", "").strip()[:limit]


def _paragraph(value: Any, style: ParagraphStyle, limit: int = 500) -> Paragraph:
    text = escape(_clean(value, limit)).replace("\n", "<br/>")
    return Paragraph(text or "—", style)


def _assignment_map(roster: dict[str, Any]) -> dict[int, dict[str, Any]]:
    result: dict[int, dict[str, Any]] = {}
    assignments = roster.get("assignments")
    if not isinstance(assignments, list):
        return result
    seen_trains: set[str] = set()
    for item in assignments:
        if not isinstance(item, dict):
            continue
        try:
            slot = int(item["slot"])
            train_no = str(item["trainNo"])
        except (KeyError, TypeError, ValueError):
            continue
        if slot not in range(1, 21) or slot in result or train_no in seen_trains:
            continue
        result[slot] = item
        seen_trains.add(train_no)
    return result


def _hierarchy_ok(image_path: Path) -> bool:
    try:
        xml = image_path.with_suffix(".xml").read_text(encoding="utf-8", errors="replace")
        return len(xml) > 100 and 'package="in.opentrack.india"' in xml
    except OSError:
        return False


def _report_validation_failures(
    slot: int,
    result: dict[str, Any] | None,
    assignment: dict[str, Any] | None,
    observations: list[dict[str, Any]],
    screenshots: list[tuple[int, Path]],
    gate_before_emulator: dict[str, Any] | None,
    gate_after_boot: dict[str, Any] | None,
) -> list[str]:
    if not assignment or not result or result.get("passed") is not True:
        return []
    failures: list[str] = []
    if result.get("slot") != slot:
        failures.append("result_slot_mismatch")
    if str(result.get("trainNo")) != str(assignment.get("trainNo")):
        failures.append("result_train_mismatch")
    if result.get("runDate") != assignment.get("runDate"):
        failures.append("result_run_date_mismatch")
    try:
        if int(result.get("durationMs", 0)) < 45 * 60_000:
            failures.append("duration_below_45_minutes")
    except (TypeError, ValueError, OverflowError):
        failures.append("duration_missing_or_invalid")
    minutes = [_minute(row) for row in observations]
    if len(observations) != 46 or sorted(minutes) != list(range(46)):
        failures.append("observation_minutes_incomplete_or_duplicated")
    if result.get("observationCount") != 46 or result.get("expectedObservationCount") != 46:
        failures.append("result_observation_count_not_confirmed")
    if any(row.get("screenPresent") is not True or not row.get("sampledAtIst") for row in observations):
        failures.append("one_or_more_screen_samples_missing")
    if any(row.get("autoEnabled") is not True for row in observations):
        failures.append("auto_not_confirmed_on_at_every_sample")
    if any(str(row.get("trainNo") or "") != str(assignment.get("trainNo"))
           or not str(row.get("route") or "").strip() for row in observations):
        failures.append("observation_train_or_route_identity_missing")
    receipts, missed = _receipt_metrics(observations)
    if result.get("screenPresentSamples") != 46:
        failures.append("result_screen_sample_count_not_confirmed")
    if receipts < 43:
        failures.append("fewer_than_43_refresh_receipts")
    try:
        if int(result.get("minuteRefreshReceipts")) != receipts or \
                int(result.get("missedOrUnchangedReceiptSamples")) != missed:
            failures.append("reported_refresh_receipts_do_not_match_observations")
    except (TypeError, ValueError, OverflowError):
        failures.append("reported_refresh_receipt_metrics_missing")
    if result.get("deviceStartEligible") is not True:
        failures.append("device_start_eligibility_not_confirmed")
    for label, gate in (("before_emulator", gate_before_emulator), ("after_boot", gate_after_boot)):
        if not isinstance(gate, dict) or gate.get("eligible") is not True:
            failures.append("host_eligibility_gate_" + label + "_not_confirmed")
            continue
        try:
            age = float(gate.get("preflightSourceStampAgeSecondsAtStart"))
            if age < 0 or age > 10 * 60:
                failures.append("host_eligibility_gate_" + label + "_source_stamp_not_fresh")
        except (TypeError, ValueError, OverflowError):
            failures.append("host_eligibility_gate_" + label + "_source_stamp_age_missing")
        if not str(gate.get("checkedAtUtc") or "").strip():
            failures.append("host_eligibility_gate_" + label + "_timestamp_missing")
    if result.get("autoOnAtEverySample") is not True or result.get("autoOffAtEnd") is not True:
        failures.append("auto_state_acceptance_not_confirmed")
    if not str(result.get("startedAtUtc") or "").strip() or not str(result.get("endedAtUtc") or "").strip():
        failures.append("observation_timestamps_missing")
    for key in ("selectedSourceAtStart", "selectedSourceAtEnd"):
        if "Selected NTES · official" not in str(result.get(key) or ""):
            failures.append(key + "_not_confirmed")
    screenshot_minutes = {minute for minute, path in screenshots
                          if png_ok(path) and _hierarchy_ok(path)}
    if not {0, 15, 30, 45}.issubset(screenshot_minutes):
        failures.append("required_screenshot_or_app_hierarchy_missing")
    return failures


def _slot_state(
    slot_result: dict[str, Any] | None,
    assignment: dict[str, Any] | None,
    slot: int,
    observations: list[dict[str, Any]],
    screenshots: list[tuple[int, Path]],
    gate_before_emulator: dict[str, Any] | None,
    gate_after_boot: dict[str, Any] | None,
) -> str:
    if not assignment:
        if slot_result:
            return str(slot_result.get("status", "NO_ASSIGNMENT"))
        return "NO_ELIGIBLE_CANDIDATE"
    if not slot_result:
        return "MISSING_MATRIX_RESULT"
    if slot_result.get("passed") is True:
        failures = _report_validation_failures(
            slot, slot_result, assignment, observations, screenshots,
            gate_before_emulator, gate_after_boot)
        return "PASS_45_MINUTE_UI_TRIAL" if not failures else "RESULT_REJECTED_INCOMPLETE_EVIDENCE"
    return str(slot_result.get("status") or "FAILED_OR_INCOMPLETE")


def _receipt_metrics(observations: list[dict[str, Any]]) -> tuple[int, int]:
    receipts = sum(1 for row in observations
                   if 1 <= _minute(row) <= 45 and
                   row.get("newReceiptSincePrevious") is True)
    return receipts, max(0, 45 - receipts)


def _slot_summary(
    slot: int,
    assignment: dict[str, Any] | None,
    result: dict[str, Any] | None,
    observations: list[dict[str, Any]],
    screenshots: list[tuple[int, Path]],
    gate_before_emulator: dict[str, Any] | None = None,
    gate_after_boot: dict[str, Any] | None = None,
) -> dict[str, Any]:
    receipts, missed = _receipt_metrics(observations)
    screen_samples = sum(
        row.get("screenPresent") is True and bool(str(row.get("sampledAtIst") or "").strip())
        for row in observations
    )
    validation_failures = _report_validation_failures(
        slot, result, assignment, observations, screenshots, gate_before_emulator, gate_after_boot)
    if assignment:
        feed = assignment.get("preflightSourceEvidence") or {}
        try:
            duration_minutes = round(int((result or {}).get("durationMs") or 0) / 60_000, 2)
        except (TypeError, ValueError, OverflowError):
            duration_minutes = 0
        return {
            "slot": slot,
            "trainNo": assignment.get("trainNo"),
            "trainName": assignment.get("trainName"),
            "route": f"{assignment.get('sourceStationCode', '?')} → {assignment.get('destinationStationCode', '?')}",
            "runDate": assignment.get("runDate"),
            "scheduleEligibilityAtPreflight": assignment.get("scheduleEligibility"),
            "scheduleEligibilityAtDeviceStart": (result or {}).get("deviceStartScheduleEligibility"),
            "preflightHttpStatus": feed.get("httpStatus"),
            "preflightFreshStamp": feed.get("freshSourceStamp") is True,
            "preflightStampAgeSeconds": feed.get("sourceStampAgeSecondsAtProbe"),
            "preflightRunDateMatched": feed.get("runDateMatched") is True,
            "preflightRouteMatched": feed.get("routeMatched") is True,
            "preflightScheduleTimesMatched": feed.get("scheduleEndpointTimesMatched") is True,
            "eligibilityGateBeforeEmulator": gate_before_emulator,
            "eligibilityGateAfterBoot": gate_after_boot,
            "status": _slot_state(result, assignment, slot, observations, screenshots,
                                   gate_before_emulator, gate_after_boot),
            "durationMinutes": duration_minutes,
            "observationCount": len(observations),
            "actualScreenObservations": screen_samples,
            "screenPresentSamples": sum(row.get("screenPresent") is True for row in observations),
            "minuteRefreshReceipts": receipts,
            "reportedMinuteRefreshReceipts": (result or {}).get("minuteRefreshReceipts"),
            "missedOrUnchangedReceiptSamples": missed,
            "autoOffAtEnd": (result or {}).get("autoOffAtEnd") is True,
            "selectedSource": (result or {}).get("selectedSourceAtStart"),
            "screenshots": [row.get("screenshot") for row in observations if row.get("screenshot")],
            "reportValidationFailures": validation_failures,
            "failure": _clean((result or {}).get("failure") or (result or {}).get("reason"), 500),
        }
    return {
        "slot": slot,
        "trainNo": None,
        "trainName": None,
        "route": None,
        "runDate": None,
        "scheduleEligibilityAtPreflight": None,
        "scheduleEligibilityAtDeviceStart": None,
        "preflightHttpStatus": None,
        "preflightFreshStamp": False,
        "preflightStampAgeSeconds": None,
        "preflightRunDateMatched": False,
        "preflightRouteMatched": False,
        "preflightScheduleTimesMatched": False,
        "eligibilityGateBeforeEmulator": gate_before_emulator,
        "eligibilityGateAfterBoot": gate_after_boot,
        "status": _slot_state(result, assignment, slot, observations, screenshots,
                               gate_before_emulator, gate_after_boot),
        "durationMinutes": 0,
        "observationCount": len(observations),
        "actualScreenObservations": screen_samples,
        "screenPresentSamples": sum(row.get("screenPresent") is True for row in observations),
        "minuteRefreshReceipts": receipts,
        "reportedMinuteRefreshReceipts": (result or {}).get("minuteRefreshReceipts"),
        "missedOrUnchangedReceiptSamples": missed,
        "autoOffAtEnd": (result or {}).get("autoOffAtEnd") is True,
        "selectedSource": (result or {}).get("selectedSourceAtStart"),
        "screenshots": [row.get("screenshot") for row in observations if row.get("screenshot")],
        "reportValidationFailures": validation_failures,
        "failure": _clean((result or {}).get("failure") or (result or {}).get("reason"), 500),
    }


def _frame(canvas, doc) -> None:
    canvas.saveState()
    width, height = doc.pagesize
    canvas.setStrokeColor(colors.HexColor("#D8E0E8"))
    canvas.setLineWidth(0.5)
    canvas.line(doc.leftMargin, 17 * mm, width - doc.rightMargin, 17 * mm)
    canvas.setFont("Helvetica", 7)
    canvas.setFillColor(colors.HexColor("#526174"))
    canvas.drawString(doc.leftMargin, 11 * mm, "Checktrain02 · 20-train emulator campaign · source evidence is not independent verification")
    canvas.drawRightString(width - doc.rightMargin, 11 * mm, f"Page {doc.page}")
    canvas.restoreState()


def build_report(
    roster_path: Path,
    slots_dir: Path,
    output_pdf: Path,
    output_json: Path,
    output_markdown: Path,
    metadata: dict[str, str] | None = None,
) -> dict[str, Any]:
    metadata = metadata or {}
    roster = read_json(roster_path, {})
    if not isinstance(roster, dict):
        roster = {}
    assignment_by_slot = _assignment_map(roster)
    slot_results: dict[int, dict[str, Any] | None] = {}
    observations_by_slot: dict[int, list[dict[str, Any]]] = {}
    screenshot_paths: dict[int, list[tuple[int, Path]]] = {}
    summaries = []

    for slot in range(1, 21):
        directory = slots_dir / f"slot-{slot:02d}"
        result = read_json(directory / "slot-result.json")
        if not isinstance(result, dict):
            result = None
        gate_before = read_json(directory / "eligibility-before-emulator.json")
        if not isinstance(gate_before, dict):
            gate_before = None
        gate_after_boot = read_json(directory / "eligibility-after-emulator-boot.json")
        if not isinstance(gate_after_boot, dict):
            gate_after_boot = None
        observations = read_observations(directory / "observations.jsonl")
        slot_results[slot] = result
        observations_by_slot[slot] = observations
        shots: list[tuple[int, Path]] = []
        for row in observations:
            name = row.get("screenshot")
            if not isinstance(name, str):
                continue
            image = directory / name
            try:
                contained = directory.resolve() in image.resolve().parents
            except OSError:
                contained = False
            if contained and png_ok(image):
                shots.append((_minute(row), image))
        screenshot_paths[slot] = sorted(shots)
        summaries.append(_slot_summary(
            slot, assignment_by_slot.get(slot), result, observations, screenshot_paths[slot],
            gate_before, gate_after_boot))

    completed = sum(row["status"] == "PASS_45_MINUTE_UI_TRIAL" for row in summaries)
    assigned = len(assignment_by_slot)
    campaign_ready = roster.get("status") == "READY" and assigned == 20
    full_pass = campaign_ready and completed == 20
    timetable = roster.get("timetable")
    if not isinstance(timetable, dict):
        timetable = {}
    actual_observations = sum(row["actualScreenObservations"] for row in summaries)
    observation_records = sum(row["observationCount"] for row in summaries)
    report = {
        "schemaVersion": 1,
        "campaign": "wimt-20-train-45-minute-emulator-campaign",
        "verdict": "PASS_20_OF_20" if full_pass else
            "PARTIAL_OR_INCOMPLETE" if assigned else "NO_ELIGIBLE_ROSTER_OR_PREPARATION_FAILED",
        "passed": full_pass,
        "requiredIndependentEmulatorJobs": 20,
        "assignedDistinctTrains": assigned,
        "completed45MinuteUiTrials": completed,
        "totalMinuteSlotsObserved": actual_observations,
        "totalObservationRecords": observation_records,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "workflowRunId": metadata.get("run_id"),
        "repository": metadata.get("repository"),
        "branch": metadata.get("ref_name"),
        "commit": metadata.get("sha"),
        "rosterStatus": roster.get("status", "unavailable"),
        "rosterStopReason": roster.get("stopReason"),
        "probesAttempted": roster.get("probesAttempted"),
        "rejectionCounts": roster.get("rejectionCounts", {}),
        "timetable": timetable,
        "sourceIdentity": roster.get("sourceIdentity", {}),
        "eligibilityRule": roster.get("eligibilityRule", "not recorded"),
        "sourceLimitations": PROVENANCE,
        "autoRefreshLimitations": AUTO_LIMIT,
        "slots": summaries,
    }
    output_json.parent.mkdir(parents=True, exist_ok=True)
    output_json.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    lines = [
        "# 20-train, 45-minute emulator campaign",
        "",
        f"- Verdict: **{report['verdict']}**",
        f"- Roster: **{assigned}/20 distinct train assignments** ({roster.get('status', 'unavailable')})",
        f"- Completed 45-minute installed-app trials: **{completed}/20**",
        f"- Screen samples actually observed: **{report['totalMinuteSlotsObserved']}**",
        f"- Observation records (including explicit missing-sample gaps): **{report['totalObservationRecords']}**",
        f"- Workflow run: {metadata.get('run_id') or 'not recorded'}",
        f"- Commit: `{metadata.get('sha') or 'not recorded'}`",
        f"- Reference-source history vintage: {report['timetable'].get('sourceVintage') or 'unknown'}",
        f"- Timetable vintage/freshness: {report['timetable'].get('timetableVintage') or 'unknown'} / "
        f"{report['timetable'].get('timetableFreshness', 'unknown')}",
        f"- Run-day overlay vintage: {report['timetable'].get('runDaysVintage') or 'unknown'}",
        f"- Timetable data-trust label: {report['timetable'].get('dataTrust', 'unverified')}",
        f"- Known timetable concerns: {report['timetable'].get('dataTruthProblems') or 'none recorded'}",
        "",
        "The installed-app test is coded to require **NTES · official** and records the selected-source "
        "description per slot. Roster preflight separately queried the direct **WIMT .in** feed. WIMT "
        "`.org.in` website claims, eRail, WIMT web-cache data, and Google WIMT are not merged or counted "
        "as live actuals. No independent movement/GPS claim is made.",
        "",
        "| Slot | Train | Run date | Eligibility | UI trial | Duration | Screen samples | Receipts |",
        "|---:|---:|---|---|---|---:|---:|---:|",
    ]
    for row in summaries:
        lines.append(
            f"| {row['slot']} | {row['trainNo'] or '—'} | {row['runDate'] or '—'} | "
            f"{row['scheduleEligibilityAtDeviceStart'] or row['scheduleEligibilityAtPreflight'] or '—'} | "
            f"{row['status']} | {row['durationMinutes']:.2f} min | {row['actualScreenObservations']}/46 | "
            f"{row['minuteRefreshReceipts']} |"
        )
    lines.extend(["", AUTO_LIMIT, "", PROVENANCE, ""])
    output_markdown.parent.mkdir(parents=True, exist_ok=True)
    output_markdown.write_text("\n".join(lines), encoding="utf-8")

    styles = getSampleStyleSheet()
    title = ParagraphStyle("CampaignTitle", parent=styles["Title"], fontName="Helvetica-Bold",
                           fontSize=20, leading=24, textColor=colors.HexColor("#15314B"),
                           alignment=TA_LEFT, spaceAfter=10)
    h1 = ParagraphStyle("CampaignH1", parent=styles["Heading1"], fontSize=15, leading=19,
                        textColor=colors.HexColor("#15314B"), spaceBefore=6, spaceAfter=8)
    h2 = ParagraphStyle("CampaignH2", parent=styles["Heading2"], fontSize=11, leading=14,
                        textColor=colors.HexColor("#1F4664"), spaceBefore=4, spaceAfter=5)
    body = ParagraphStyle("CampaignBody", parent=styles["BodyText"], fontSize=8.5, leading=11,
                          textColor=colors.HexColor("#233142"), spaceAfter=4)
    small = ParagraphStyle("CampaignSmall", parent=body, fontSize=7, leading=8.5, spaceAfter=0)
    tiny = ParagraphStyle("CampaignTiny", parent=small, fontSize=6.2, leading=7.5)
    center = ParagraphStyle("CampaignCenter", parent=small, alignment=TA_CENTER)

    output_pdf.parent.mkdir(parents=True, exist_ok=True)
    page_size = landscape(A4)
    document = SimpleDocTemplate(str(output_pdf), pagesize=page_size,
                                 rightMargin=14 * mm, leftMargin=14 * mm,
                                 topMargin=13 * mm, bottomMargin=22 * mm,
                                 title="20-train, 45-minute emulator campaign",
                                 author="Checktrain02 installed-app campaign")
    story = [
        Paragraph("20-train emulator campaign", title),
        Paragraph("45-minute, minute-by-minute installed-app observation", h2),
        Spacer(1, 3 * mm),
    ]
    summary_rows = [
        ["Verdict", report["verdict"], "Eligible roster", f"{assigned}/20"],
        ["45-minute UI trials", f"{completed}/20", "Minute observation slots", str(report["totalMinuteSlotsObserved"])],
        ["Roster status", str(roster.get("status", "unavailable")), "WIMT .in probes", str(roster.get("probesAttempted", 0))],
        ["Workflow run", str(metadata.get("run_id") or "not recorded"), "Commit", str((metadata.get("sha") or "not recorded")[:12])],
        ["Branch", str(metadata.get("ref_name") or "not recorded"), "Report generated", report["generatedAtUtc"]],
    ]
    summary_table = Table(summary_rows, colWidths=[34 * mm, 63 * mm, 42 * mm, 54 * mm])
    summary_table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (0, -1), colors.HexColor("#E8EEF4")),
        ("BACKGROUND", (2, 0), (2, -1), colors.HexColor("#E8EEF4")),
        ("TEXTCOLOR", (0, 0), (-1, -1), colors.HexColor("#233142")),
        ("FONTNAME", (0, 0), (0, -1), "Helvetica-Bold"),
        ("FONTNAME", (2, 0), (2, -1), "Helvetica-Bold"),
        ("FONTSIZE", (0, 0), (-1, -1), 8),
        ("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#C8D2DC")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]))
    story.extend([summary_table, Spacer(1, 5 * mm), Paragraph("Source and test boundaries", h2),
                  Paragraph(escape(PROVENANCE), body), Paragraph(escape(AUTO_LIMIT), body)])
    story.append(Paragraph("Roster gate and timetable provenance", h2))
    tmeta = report["timetable"] or {}
    story.append(Paragraph(
        f"Reference-source history vintage: <b>{escape(str(tmeta.get('sourceVintage') or 'unknown'))}</b>; "
        f"schedule vintage: <b>{escape(str(tmeta.get('timetableVintage') or 'unknown'))}</b>; "
        f"age: <b>{escape(str(tmeta.get('timetableAgeDays') if tmeta.get('timetableAgeDays') is not None else 'unknown'))}</b> days; "
        f"freshness label: <b>{escape(str(tmeta.get('timetableFreshness', 'unknown')))}</b>; "
        f"run-day overlay vintage: <b>{escape(str(tmeta.get('runDaysVintage') or 'unknown'))}</b>; "
        f"known-truth label: <b>{escape(str(tmeta.get('dataTrust', 'unverified')))}</b>. "
        f"Known scheduled candidates: {escape(str(tmeta.get('knownRunDayCandidates', 0)))}. "
        f"Known data concerns: {escape(str(tmeta.get('dataTruthProblems') or 'none recorded'))}. "
        f"Roster rule: {escape(str(report['eligibilityRule']))}", body))
    story.append(Paragraph("Slot overview", h2))
    overview_data = [["Slot", "Train", "Route", "Date", "Eligibility", "Result", "Min", "Receipts"]]
    for row in summaries:
        overview_data.append([
            str(row["slot"]), str(row["trainNo"] or "—"), str(row["route"] or "—"),
            str(row["runDate"] or "—"), str(row["scheduleEligibilityAtDeviceStart"] or
                                              row["scheduleEligibilityAtPreflight"] or "—"),
            str(row["status"]), f"{row['durationMinutes']:.1f}",
            f"{row['minuteRefreshReceipts']}/45",
        ])
    overview = Table(overview_data, repeatRows=1,
                     colWidths=[12 * mm, 20 * mm, 36 * mm, 24 * mm, 47 * mm, 58 * mm, 14 * mm, 18 * mm])
    overview.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#183B56")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ("FONTSIZE", (0, 0), (-1, -1), 6.8),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F3F6F8")]),
        ("GRID", (0, 0), (-1, -1), 0.3, colors.HexColor("#C8D2DC")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("TOPPADDING", (0, 0), (-1, -1), 3),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
    ]))
    story.extend([overview, PageBreak()])

    for slot in range(1, 21):
        assignment = assignment_by_slot.get(slot)
        result = slot_results[slot]
        observations = observations_by_slot[slot]
        summary = summaries[slot - 1]
        story.append(Paragraph(f"Slot {slot:02d} · {escape(str(summary['trainNo'] or 'no train assigned'))}", h1))
        if assignment:
            feed = assignment.get("preflightSourceEvidence") or {}
            gate_before = summary.get("eligibilityGateBeforeEmulator") or {}
            gate_after_boot = summary.get("eligibilityGateAfterBoot") or {}
            fields = [
                ("Train", f"{assignment.get('trainNo')} · {assignment.get('trainName') or 'name unavailable'}"),
                ("Route / run date", f"{assignment.get('sourceStationCode')} → {assignment.get('destinationStationCode')} · {assignment.get('runDate')}"),
                ("Preflight schedule eligibility", f"{assignment.get('scheduleEligibility')} · "
                 f"dep {assignment.get('scheduledDepartureMinute')} min / arr {assignment.get('scheduledArrivalMinute')} min from run-date midnight"),
                ("At device start", str((result or {}).get("deviceStartScheduleEligibility", "not recorded")) +
                 f" · due delta {(result or {}).get('deviceStartMinutesToDeparture', '—')} min · "
                 f"remaining {(result or {}).get('deviceStartScheduledMinutesRemaining', '—')} min"),
                ("Host eligibility before emulator", f"eligible {gate_before.get('eligible', 'not recorded')} · "
                 f"{gate_before.get('scheduleEligibilityAtStart', 'schedule not recorded')} · "
                 f"preflight stamp age {gate_before.get('preflightSourceStampAgeSecondsAtStart', 'unknown')} s · "
                 f"{'; '.join(gate_before.get('reasons', [])) or 'no rejection reason recorded'}"),
                ("Host eligibility after emulator boot", f"eligible {gate_after_boot.get('eligible', 'not recorded')} · "
                 f"{gate_after_boot.get('scheduleEligibilityAtStart', 'schedule not recorded')} · "
                 f"preflight stamp age {gate_after_boot.get('preflightSourceStampAgeSecondsAtStart', 'unknown')} s · "
                 f"{'; '.join(gate_after_boot.get('reasons', [])) or 'no rejection reason recorded'}"),
                ("Direct WIMT .in preflight", f"HTTP {feed.get('httpStatus', 'unknown')} · "
                 f"run date {feed.get('runDateMatched', False)} · route {feed.get('routeMatched', False)} · "
                 f"schedule endpoint clocks {feed.get('scheduleEndpointTimesMatched', False)} · "
                 f"fresh stamp {feed.get('freshSourceStamp', False)} · age {feed.get('sourceStampAgeSecondsAtProbe', 'unknown')} s"),
                ("Feed row context", f"schedule rows {feed.get('sourceScheduleRowCount', 'unknown')} · "
                 f"rows with actual-like fields {feed.get('sourceRowsWithActualFields', 'unknown')} · "
                 f"current-station field present {feed.get('sourceCurrentStationFieldPresent', False)}"),
                ("Installed app trial", f"{summary['status']} · {summary['durationMinutes']:.2f}/45 min · "
                 f"observation records {summary['observationCount']}/46 · actual screen samples "
                 f"{summary['actualScreenObservations']}/46 · minute receipts "
                 f"{summary['minuteRefreshReceipts']}/45 · missed/unchanged "
                 f"{summary['missedOrUnchangedReceiptSamples']}"),
                ("Auto / selected source", f"enabled at start {(result or {}).get('autoEnabledAtStart', False)} · "
                 f"OFF at end {summary['autoOffAtEnd']} · "
                 f"{summary['selectedSource'] or 'source selection not recorded'}"),
                ("Report evidence validation", ", ".join(summary["reportValidationFailures"])
                 if summary["reportValidationFailures"] else "no report-side rejection"),
            ]
            facts = [[Paragraph(f"<b>{escape(label)}</b>", small), Paragraph(escape(value), small)]
                     for label, value in fields]
            fact_table = Table(facts, colWidths=[45 * mm, 225 * mm])
            fact_table.setStyle(TableStyle([
                ("BACKGROUND", (0, 0), (0, -1), colors.HexColor("#E8EEF4")),
                ("GRID", (0, 0), (-1, -1), 0.3, colors.HexColor("#C8D2DC")),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("TOPPADDING", (0, 0), (-1, -1), 4),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
            ]))
            story.append(fact_table)
        else:
            story.append(Paragraph(
                f"No distinct eligible train was assigned to this slot. Status: "
                f"{escape(summary['status'])}. {escape(summary['failure'] or 'No qualifying candidate was available at roster time.')}", body))

        if result and result.get("failure"):
            story.append(Paragraph("Device failure detail: " + escape(_clean(result.get("failure"), 700)), body))
        if result and result.get("reason"):
            story.append(Paragraph("Slot disposition: " + escape(_clean(result.get("reason"), 700)), body))

        story.append(Spacer(1, 4 * mm))
        story.append(Paragraph("Minute-by-minute accessible-screen observations", h2))
        table_header = ["Min", "Elapsed", "Captured IST", "Selected source", "Age", "Headline / provenance", "Refresh receipt", "Screen", "Auto"]
        table_data: list[list[Any]] = [[Paragraph(escape(cell), tiny) for cell in table_header]]
        for row in observations:
            elapsed_min = int(row.get("elapsedMs") or 0) / 60_000
            selected = row.get("sourceDescription") or row.get("sourceLabel") or ""
            if " Last completed refresh: " in selected:
                selected = selected.split(" Last completed refresh: ", 1)[0]
            if ". Choose " in selected:
                selected = selected.split(". Choose ", 1)[0]
            detail = " · ".join(part for part in (
                _clean(row.get("headline"), 130), _clean(row.get("provenance"), 110),
                _clean(row.get("sourceSummary"), 90)) if part)
            receipt = row.get("receiptDescription") or row.get("receiptClock") or "no new receipt"
            table_data.append([
                str(row.get("minute", "—")), f"{elapsed_min:.1f}m",
                _paragraph(row.get("sampledAtIst"), tiny),
                _paragraph(selected, tiny, 130), _paragraph(row.get("age"), tiny, 60),
                _paragraph(detail, tiny, 250),
                _paragraph(receipt, tiny, 180),
                "yes" if row.get("screenPresent") is True else "no",
                "on" if row.get("autoEnabled") is True else "off/unknown",
            ])
        if len(table_data) == 1:
            table_data.append(["—", "—", "—", "—", "—", "No device observations were recovered.", "—", "no", "—"])
        column_widths = [9 * mm, 13 * mm, 28 * mm, 32 * mm, 17 * mm, 70 * mm, 57 * mm, 12 * mm, 18 * mm]
        observation_table = Table(table_data, repeatRows=1, colWidths=column_widths, splitByRow=1)
        observation_table.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#183B56")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
            ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
            ("FONTSIZE", (0, 0), (-1, -1), 6.2),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F5F7F9")]),
            ("GRID", (0, 0), (-1, -1), 0.25, colors.HexColor("#CBD4DD")),
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("LEFTPADDING", (0, 0), (-1, -1), 2),
            ("RIGHTPADDING", (0, 0), (-1, -1), 2),
            ("TOPPADDING", (0, 0), (-1, -1), 2),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 2),
        ]))
        story.append(observation_table)

        photos = screenshot_paths[slot]
        if photos:
            story.append(PageBreak())
            story.append(Paragraph(f"Slot {slot:02d} · installed-app screenshot evidence", h2))
            story.append(Paragraph(
                "PNG captures and matching XML hierarchy were taken inside the running emulator. "
                "The PNGs are embedded without image edits; the accessible-screen observations above "
                "remain the minute-by-minute log.", small))
            for index, (minute, image_path) in enumerate(photos):
                if index > 0:
                    story.append(PageBreak())
                story.append(Paragraph(
                    f"Train {escape(str(summary['trainNo'] or '—'))} · observation minute {minute}", h2))
                image = Image(str(image_path), width=page_size[0] - 40 * mm,
                              height=page_size[1] - 72 * mm, kind="proportional")
                story.append(image)
        story.append(PageBreak())

    story.append(Paragraph("Consolidated disposition", h1))
    story.append(Paragraph(
        f"{completed} of 20 assigned matrix slots passed the 45-minute UI acceptance. "
        f"The roster contains {assigned} distinct assignments. Workflow result: "
        f"<b>{escape(report['verdict'])}</b>.", body))
    story.append(Paragraph(escape(PROVENANCE), body))
    document.build(story, onFirstPage=_frame, onLaterPages=_frame)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--roster", type=Path, required=True)
    parser.add_argument("--slots-dir", type=Path, required=True)
    parser.add_argument("--output-pdf", type=Path, required=True)
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--output-markdown", type=Path, required=True)
    args = parser.parse_args()
    import os
    metadata = {
        "run_id": os.environ.get("GITHUB_RUN_ID", ""),
        "repository": os.environ.get("GITHUB_REPOSITORY", ""),
        "ref_name": os.environ.get("GITHUB_REF_NAME", ""),
        "sha": os.environ.get("GITHUB_SHA", ""),
    }
    report = build_report(args.roster, args.slots_dir, args.output_pdf,
                          args.output_json, args.output_markdown, metadata)
    print(json.dumps({
        "pdf": str(args.output_pdf),
        "verdict": report["verdict"],
        "assigned": report["assignedDistinctTrains"],
        "passed": report["completed45MinuteUiTrials"],
        "observations": report["totalMinuteSlotsObserved"],
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
