#!/usr/bin/env python3
"""Create an evidence PDF only from verified fresh installed-app comparison captures."""
from pathlib import Path
import json
import re
import sys

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import ImageReader, simpleSplit
from reportlab.pdfgen import canvas

from verify_wimt_live_comparison_screens import verify

ROLE_LABELS = {
    "appLive": "Installed app · selected WIMT `.in` live-status view",
    "appDetails": "Installed app · selected WIMT `.in` source details",
    "webCache": "Installed app · selected WIMT web-cache comparison view",
    "webDetails": "Installed app · WIMT web-cache comparison details",
}


def _draw_wrapped(pdf, text: str, x: float, y: float, width: float,
                  font: str = "Helvetica", size: float = 9.2,
                  leading: float = 13.0) -> float:
    pdf.setFont(font, size)
    for line in simpleSplit(text, font, size, width):
        pdf.drawString(x, y, line)
        y -= leading
    return y


def make_pdf(folder: Path, output: Path, source_run: str, source_sha: str) -> dict:
    result_path = folder / "result.json"
    log_path = folder / "device-log.txt"
    if not result_path.is_file() or not log_path.is_file():
        raise ValueError("the installed-test result and device log are required")
    result = json.loads(result_path.read_text(encoding="utf-8"))
    if not source_run.isdigit() or not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("a GitHub Actions run number and exact commit SHA are required")
    verification = verify(folder, log_path.read_text(encoding="utf-8", errors="replace"))
    comparisons = verification.get("comparisons", [])
    if not comparisons:
        raise ValueError("no completed comparison has verified fresh in-app screenshots")
    if result.get("completedComparisons") != len(comparisons):
        raise ValueError("summary result and verified screenshot count disagree")

    output.parent.mkdir(parents=True, exist_ok=True)
    pdf = canvas.Canvas(str(output), pagesize=A4)
    width, height = A4
    margin = 42
    accepted = result.get("accepted") is True
    pdf.setTitle("WIMT `.in` installed-app comparison evidence · run " + source_run)
    pdf.setAuthor("TrackTrain installed-app screenshots; source claims only")

    # Report page: explicitly label this as two parser/source paths to one endpoint, not independent parity.
    pdf.setFillColor(colors.HexColor("#132238"))
    pdf.rect(0, height - 92, width, 92, fill=1, stroke=0)
    pdf.setFillColor(colors.white)
    pdf.setFont("Helvetica-Bold", 16)
    pdf.drawString(margin, height - 42,
                   "WIMT `.in` comparison: " + ("COMPLETE" if accepted else "INCOMPLETE"))
    pdf.setFont("Helvetica", 9)
    pdf.drawString(margin, height - 63,
                   f"{len(comparisons)} verified same-train/date comparisons · Actions run {source_run}")
    y = height - 112
    intro = (
        "This compares TrackTrain's selected WIMT `.in` source path with its explicit WIMT web-cache "
        "comparison path. Both make ordinary requests to the same undocumented "
        "whereismytrain.in/cache/live_status endpoint; this is not independent-source parity, the "
        "whereismytrain.org.in website, Google's WIMT Android app, GPS, or verified railway movement."
    )
    y = _draw_wrapped(pdf, intro, margin, y, width - 2 * margin, "Helvetica", 9.2, 13)
    y -= 4
    y = _draw_wrapped(pdf, f"Source commit: {source_sha}", margin, y,
                      width - 2 * margin, "Helvetica-Bold", 9.2, 13)
    y = _draw_wrapped(pdf, "Run verdict: " + str(result.get("verdict")), margin, y,
                      width - 2 * margin, "Helvetica-Bold", 9.2, 13)
    y -= 6

    for item in comparisons:
        if y < 225:
            pdf.showPage()
            y = height - margin
        train = item.get("trainNo", "?")
        date = item.get("serviceDate", "unknown")
        direct = item.get("directSource", {})
        web = item.get("webCacheComparison", {})
        events = item.get("eventComparison", {})
        pdf.setFillColor(colors.HexColor("#1c4467"))
        pdf.setFont("Helvetica-Bold", 11)
        pdf.drawString(margin, y, f"Train {train} · run of {date}")
        y -= 16
        pdf.setFillColor(colors.HexColor("#14283b"))
        train_match = item.get("trainNumberMatch", {})
        for line in (
            "Train identity: app screen and web request use " + str(train) +
            " · response train-number echo matched=" + str(train_match.get("webResponseEchoMatched")),
            "Selected app source: " + str(direct.get("status")) +
            f" · fresh stamp={direct.get('freshSourceUpdate')} · run-date match={direct.get('responseRunDateMatched')} · route match={direct.get('bothRouteEndpointsMatched')}",
            "Direct source-reported current-station claim=" + str(direct.get("currentStationClaimVisible")) +
            f" · source rows={direct.get('sourceReportedRows')} · visible attributed time cells={direct.get('visibleSourceAttributedCells')}",
            "Web-cache comparison: " + str(web.get("status")) +
            f" · row-update fresh={web.get('rowUpdateFresh')} · freshest row age={web.get('freshestRowAgeSeconds')} s · service-date match={web.get('serviceDateMatched')} · route match={web.get('routeMatched')}",
            "Web-cache response train-number echo: " + str(web.get("responseTrainNumberEchoMatched")) +
            f" · source rows={web.get('sourceReportedRows')}",
            "Event fields: overlap=" + str(events.get("overlappingFields")) +
            f" · exact matches={events.get('matchedFields')} · all event values match={events.get('eventValuesMatch')} · independent-source parity=false",
        ):
            y = _draw_wrapped(pdf, line, margin + 4, y, width - 2 * margin - 8,
                              "Helvetica", 8.6, 11)
        details = []
        for key, label in (("mismatches", "MISMATCH"),
                           ("onlyInSelectedAppSource", "APP-ONLY"),
                           ("onlyInWebCacheComparison", "WEB-CACHE-ONLY")):
            for value in events.get(key, []) or []:
                details.append(f"{label}: {value}")
        if details:
            for line in details:
                y = _draw_wrapped(pdf, line, margin + 12, y, width - 2 * margin - 16,
                                  "Helvetica-Bold", 8.3, 10.5)
        else:
            y = _draw_wrapped(pdf, "No overlapping event-time or row-presence mismatch was observed.",
                              margin + 12, y, width - 2 * margin - 16,
                              "Helvetica", 8.3, 10.5)
        y -= 9

    pdf.setFillColor(colors.HexColor("#596a7a"))
    pdf.setFont("Helvetica", 7.5)
    pdf.drawString(margin, 25,
                   "Freshness and source identity are shown separately; schedule/fallback/ETA values are not accepted as live events.")
    pdf.showPage()

    screenshot_count = 0
    for item in comparisons:
        train = item["trainNo"]
        date = item["serviceDate"]
        for screenshot in item.get("screenshots", []):
            role = screenshot.get("role")
            name = screenshot.get("name")
            if role not in ROLE_LABELS or not isinstance(name, str):
                raise ValueError("verified comparison has an unknown screenshot role")
            image = folder / "screens" / name
            if not image.is_file():
                raise ValueError("verified screenshot file is missing: " + name)
            pdf.setFillColor(colors.HexColor("#132238"))
            pdf.setFont("Helvetica-Bold", 12)
            pdf.drawString(margin, height - 34, f"Train {train} · run of {date}")
            pdf.setFont("Helvetica", 9)
            pdf.drawString(margin, height - 51, ROLE_LABELS[role])
            pdf.setFont("Helvetica", 8)
            pdf.drawString(margin, height - 66,
                           "Unmodified screenshot captured inside the running installed APK · Actions run " + source_run)
            iw, ih = ImageReader(str(image)).getSize()
            available_width = width - 2 * margin
            available_height = height - 116
            scale = min(available_width / iw, available_height / ih)
            sw, sh = iw * scale, ih * scale
            pdf.drawImage(str(image), (width - sw) / 2, height - 82 - sh,
                          sw, sh, preserveAspectRatio=True, mask="auto")
            pdf.setFillColor(colors.HexColor("#596a7a"))
            pdf.setFont("Helvetica", 7.5)
            pdf.drawString(margin, 23,
                           "Genuine app-window capture; PNG and matching UiAutomator hierarchy passed verification.")
            pdf.showPage()
            screenshot_count += 1

    pdf.save()
    return {
        "pdf": str(output),
        "pages": 1 + screenshot_count,
        "sourceRun": source_run,
        "sourceSha": source_sha,
        "accepted": accepted,
        "comparisonCount": len(comparisons),
        "screenshotCount": screenshot_count,
        "eventMismatchCount": result.get("eventMismatchCount"),
        "bytes": output.stat().st_size,
        "screenshots": [screen.get("name") for item in comparisons
                         for screen in item.get("screenshots", [])],
    }


def main() -> int:
    if len(sys.argv) != 5:
        raise SystemExit("usage: build_wimt_live_comparison_pdf.py <evidence-dir> <output.pdf> <run-id> <40-char-sha>")
    try:
        result = make_pdf(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], sys.argv[4])
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"WIMT_LIVE_COMPARISON_PDF_NOT_BUILT: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
