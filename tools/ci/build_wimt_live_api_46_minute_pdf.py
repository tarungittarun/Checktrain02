#!/usr/bin/env python3
"""Render the one-train WIMT 46-minute report and verified, unaltered app captures."""
from __future__ import annotations

import argparse
import json
from html import escape
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import Image, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

from wimt_live_api_46_minute_report import valid_png


def _verified_images(evidence: Path, result: dict) -> list[tuple[str, Path]]:
    found: list[tuple[str, Path]] = []
    for value in result.get("screenshots", []):
        if not isinstance(value, str):
            continue
        image = evidence / value
        try:
            if evidence.resolve() not in image.resolve().parents:
                continue
        except OSError:
            continue
        hierarchy = image.with_suffix(".xml")
        try:
            xml = hierarchy.read_text(encoding="utf-8", errors="replace")
            valid = (valid_png(image) and len(xml) > 100
                     and 'package="in.opentrack.india"' in xml)
        except OSError:
            valid = False
        if valid:
            found.append((image.stem, image))
    return found


def _p(value: object, style: ParagraphStyle, limit: int = 500) -> Paragraph:
    return Paragraph(escape(str(value if value is not None else "—"))[:limit], style)


def build(evidence: Path, output_pdf: Path) -> dict:
    result_path = evidence / "result.json"
    if not result_path.is_file():
        raise SystemExit("46-minute result.json is missing")
    result = json.loads(result_path.read_text(encoding="utf-8"))
    if not isinstance(result, dict):
        raise SystemExit("46-minute result.json root must be an object")
    screenshots = _verified_images(evidence, result)
    if not screenshots:
        raise SystemExit("no genuine app-window PNGs with matching app hierarchies; no screenshot PDF created")

    styles = getSampleStyleSheet()
    title = ParagraphStyle("Wimt46Title", parent=styles["Title"], alignment=TA_LEFT,
                           fontSize=19, leading=23, textColor=colors.HexColor("#15314B"),
                           spaceAfter=6)
    heading = ParagraphStyle("Wimt46Heading", parent=styles["Heading2"], fontSize=11,
                             leading=14, textColor=colors.HexColor("#1F4664"),
                             spaceBefore=5, spaceAfter=5)
    body = ParagraphStyle("Wimt46Body", parent=styles["BodyText"], fontSize=8.5,
                          leading=11, spaceAfter=4)
    small = ParagraphStyle("Wimt46Small", parent=body, fontSize=7, leading=8.5,
                           spaceAfter=0)
    tiny = ParagraphStyle("Wimt46Tiny", parent=small, fontSize=6.2, leading=7.5)
    output_pdf.parent.mkdir(parents=True, exist_ok=True)
    document = SimpleDocTemplate(str(output_pdf), pagesize=A4,
                                 rightMargin=13 * mm, leftMargin=13 * mm,
                                 topMargin=12 * mm, bottomMargin=15 * mm,
                                 title="One-train, 46-minute WIMT installed-app observation",
                                 author="Checktrain02 installed-app test")
    story = [
        Paragraph("One-train WIMT installed-app observation", title),
        Paragraph("46 minutes · selected WIMT .in source · one-minute Auto refresh", heading),
        Paragraph("<b>Verdict:</b> " + escape(str(result.get("verdict", "unknown"))), body),
        Paragraph("<b>Train / run date:</b> " + escape(str(result.get("trainNo") or "not recorded")) +
                  " / " + escape(str(result.get("runDate") or "not recorded")), body),
        Paragraph("<b>Route:</b> " + escape(str(result.get("route") or "not recorded")), body),
        Paragraph("<b>Source:</b> " + escape(str(result.get("source") or "not recorded")), body),
    ]
    refresh = result.get("refresh") if isinstance(result.get("refresh"), dict) else {}
    story.append(Paragraph(
        f"<b>Elapsed:</b> {escape(str(result.get('observedDurationMinutes') or 'not recorded'))} / 46 min; "
        f"<b>refresh receipts:</b> {escape(str(refresh.get('receipts', 0)))}/46; "
        f"<b>missed slots:</b> {escape(str(refresh.get('missedReceiptSlots', 0)))}; "
        f"<b>Auto off at end:</b> {escape(str(result.get('checks', {}).get('AutoWasTurnedOffAtEnd', False)))}.", body))
    story.append(Paragraph(
        "A current-station entry below is a WIMT source claim, not independent evidence of movement.", body))
    story.append(Paragraph("Current-station claim checkpoints", heading))
    checkpoint_rows = [["Minute", "WIMT .in station-code claim", "HTTP", "Run/route", "Fresh", "Rows", "Qualified"]]
    for item in result.get("currentStationCheckpoints", []):
        checkpoint_rows.append([
            str(item.get("minute", "—")), str(item.get("currentStationCodeClaim") or "—"),
            str(item.get("httpStatus", "—")),
            f"{item.get('runDateMatched', False)}/{item.get('routeMatched', False)}",
            str(item.get("freshSourceStamp", False)), str(item.get("sourceRows", 0)),
            str(item.get("qualified", False)),
        ])
    checkpoint_table = Table(checkpoint_rows, repeatRows=1,
                             colWidths=[14 * mm, 45 * mm, 13 * mm, 22 * mm,
                                        16 * mm, 13 * mm, 20 * mm])
    checkpoint_table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#15314B")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ("FONTSIZE", (0, 0), (-1, -1), 6.5),
        ("LEADING", (0, 0), (-1, -1), 8),
        ("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#B9C5D1")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F1F5F8")]),
        ("LEFTPADDING", (0, 0), (-1, -1), 3),
        ("RIGHTPADDING", (0, 0), (-1, -1), 3),
        ("TOPPADDING", (0, 0), (-1, -1), 3),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
    ]))
    story.extend([checkpoint_table, Spacer(1, 5 * mm), Paragraph("Acceptance checks", heading)])
    checks = result.get("checks") if isinstance(result.get("checks"), dict) else {}
    check_rows = [["Check", "Result"]] + [[str(k), str(v)] for k, v in checks.items()]
    check_table = Table(check_rows, repeatRows=1, colWidths=[112 * mm, 22 * mm])
    check_table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#15314B")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ("FONTSIZE", (0, 0), (-1, -1), 7),
        ("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#B9C5D1")),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F1F5F8")]),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
    ]))
    story.extend([check_table, Spacer(1, 4 * mm), Paragraph("Minute-by-minute refresh receipts", heading)])
    sample_rows = [["Minute", "Elapsed (s)", "Receipt", "WIMT", "Auto", "Failed", "Cells"]]
    for item in refresh.get("samples", []):
        sample_rows.append([
            str(item.get("minute", "—")), str(item.get("elapsedSeconds", "—")),
            str(item.get("receiptClock") or "—"), str(item.get("selectedWimt", False)),
            str(item.get("autoEnabled", False)), str(item.get("receiptFailure", False)),
            str(item.get("visibleSourceCells", 0)),
        ])
    sample_table = Table(sample_rows, repeatRows=1,
                         colWidths=[15 * mm, 22 * mm, 26 * mm, 17 * mm,
                                    17 * mm, 17 * mm, 17 * mm])
    sample_table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1F4664")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ("FONTSIZE", (0, 0), (-1, -1), 6.4),
        ("LEADING", (0, 0), (-1, -1), 7.4),
        ("GRID", (0, 0), (-1, -1), 0.25, colors.HexColor("#C7D1DA")),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F5F7F9")]),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LEFTPADDING", (0, 0), (-1, -1), 2),
        ("RIGHTPADDING", (0, 0), (-1, -1), 2),
        ("TOPPADDING", (0, 0), (-1, -1), 2),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 2),
    ]))
    story.extend([sample_table, Spacer(1, 4 * mm),
                  Paragraph(escape(str(result.get("sourceProvenanceLimit", ""))), tiny)])

    for name, image_path in screenshots:
        story.append(PageBreak())
        story.append(Paragraph(escape(name), heading))
        picture = Image(str(image_path), width=180 * mm, height=230 * mm, kind="proportional")
        picture.hAlign = "CENTER"
        story.extend([picture, Spacer(1, 2 * mm),
                      Paragraph("Unaltered screen capture and matching UI hierarchy from the installed app.", small)])

    document.build(story)
    return {"pdf": str(output_pdf), "appScreenshots": len(screenshots),
            "passed": result.get("passed") is True}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence_dir", type=Path)
    parser.add_argument("output_pdf", type=Path)
    args = parser.parse_args()
    print(json.dumps(build(args.evidence_dir, args.output_pdf), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
