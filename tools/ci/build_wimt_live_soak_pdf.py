#!/usr/bin/env python3
"""Create a report PDF from the soak verdict and genuine in-app PNG captures only."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas

PROVENANCE = (
    "Source-reported WIMT .in clocks only. This is not independent railway verification, "
    "verified train movement, GPS, provider permission, the Google WIMT phone app, or the "
    "owner's Android phone. The upstream endpoint may be cached."
)


def png_ok(path: Path) -> bool:
    try:
        with path.open("rb") as stream:
            return stream.read(8) == b"\x89PNG\r\n\x1a\n" and path.stat().st_size > 1000
    except OSError:
        return False


def build(evidence: Path, output: Path) -> int:
    result_path = evidence / "result.json"
    if not result_path.is_file():
        raise SystemExit("report result.json is missing")
    result = json.loads(result_path.read_text(encoding="utf-8"))
    screenshots = [
        evidence / name for name in result.get("capturedAppScreenshots", [])
        if (evidence / name).is_file() and png_ok(evidence / name)
    ]
    if not screenshots:
        raise SystemExit("no verified in-app screenshot PNGs; refusing to make a screenshot PDF")

    output.parent.mkdir(parents=True, exist_ok=True)
    page_w, page_h = A4
    pdf = canvas.Canvas(str(output), pagesize=A4, pageCompression=1)
    pdf.setTitle("WIMT .in 240-minute installed-app regression")
    pdf.setAuthor("Checktrain02 installed-app test")
    pdf.setFont("Helvetica-Bold", 18)
    pdf.drawString(42, page_h - 54, "WIMT .in - 240-minute app regression")
    pdf.setFont("Helvetica-Bold", 13)
    pdf.drawString(42, page_h - 82, "Verdict: " + str(result.get("verdict", "UNKNOWN")))
    pdf.setFont("Helvetica", 10)
    initial = result.get("initial", {})
    refresh = result.get("autoRefresh", {})
    fields = [
        "Required duration: 240 minutes",
        "Measured duration: " + str(result.get("durationMinutes", "not recorded")) + " minutes",
        "Initial strict WIMT in-app acceptance: " + str(initial.get("accepted", False)),
        "Initial HTTP status: " + str(initial.get("httpStatus", "not recorded")),
        "Run date / route matched: " + str(initial.get("runDateMatched", False)) +
            " / " + str(initial.get("routeMatched", False)),
        "Initial fresh stamp / visible source cells: " +
            str(initial.get("freshSourceStamp", False)) + " / " +
            str(initial.get("visibleWimtAttributedCells", 0)),
        "Auto receipts / missed slots: " + str(refresh.get("receiptCycles", 0)) +
            " / " + str(refresh.get("missedReceiptSlots", 0)),
        "HTTP failures / unqualified samples: " + str(refresh.get("httpFailures", 0)) +
            " / " + str(refresh.get("unqualifiedSamples", 0)),
        "Fresh / non-fresh samples: " + str(refresh.get("freshSamples", 0)) +
            " / " + str(refresh.get("nonFreshSamples", 0)),
        "Selected-source losses / Auto off: " +
            str(refresh.get("selectedSourceLosses", 0)) + " / " +
            str(refresh.get("autoOffVerified", False)),
        "Actual in-app PNG captures: " + str(len(screenshots)),
    ]
    y = page_h - 112
    for line in fields:
        pdf.drawString(48, y, line)
        y -= 17
    pdf.setFont("Helvetica-Bold", 10)
    pdf.drawString(42, y - 3, "Provenance limit")
    y -= 20
    pdf.setFont("Helvetica", 9)
    for start in range(0, len(PROVENANCE), 95):
        pdf.drawString(48, y, PROVENANCE[start:start + 95])
        y -= 14
    pdf.setFont("Helvetica-Oblique", 9)
    pdf.drawString(42, 38, "Images below are the unaltered app-window screenshots captured by the test.")
    pdf.showPage()

    for path in screenshots:
        pdf.setFont("Helvetica-Bold", 12)
        pdf.drawString(42, page_h - 42, path.stem)
        image = ImageReader(str(path))
        iw, ih = image.getSize()
        max_w, max_h = page_w - 72, page_h - 105
        scale = min(max_w / iw, max_h / ih)
        width, height = iw * scale, ih * scale
        x, y = (page_w - width) / 2, (page_h - height) / 2 - 12
        pdf.drawImage(image, x, y, width=width, height=height, mask="auto")
        pdf.setFont("Helvetica", 8)
        pdf.drawString(42, 24, "Captured from the running installed APK; pixels not edited. " + PROVENANCE[:78])
        pdf.showPage()
    pdf.save()
    return len(screenshots)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence_dir", type=Path)
    parser.add_argument("output_pdf", type=Path)
    args = parser.parse_args()
    count = build(args.evidence_dir, args.output_pdf)
    print(json.dumps({"pdf": str(args.output_pdf), "actualInAppScreenshots": count}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
