#!/usr/bin/env python3
"""Build a PDF from actual in-test screenshots of the selected WIMT `.in` live feed."""
from pathlib import Path
import json
import sys
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas


SCREEN_NAMES = [
    ("Before selecting WIMT .in live-status", "wimt-live-before-selected-source"),
    ("After the selected WIMT .in response", "wimt-live-after-selected-source"),
    ("Selected WIMT .in source details", "wimt-live-source-details"),
]


def make_pdf(folder: Path, output: Path, source_run: str, source_sha: str) -> dict:
    result = json.loads((folder / "result.json").read_text())
    if result.get("inAppScreensVerified") is not True:
        raise ValueError("no verified in-app screenshots; refusing to make a PDF")
    if not source_run.isdigit() or len(source_sha) != 40 or \
            not all(c in "0123456789abcdef" for c in source_sha):
        raise ValueError("run ID and exact 40-character source SHA are required")
    from verify_wimt_live_screens import verify
    verified = verify(folder, accepted=result.get("accepted") is True)
    images = [(label, folder / "screens" / f"{name}.png") for label, name in SCREEN_NAMES]
    output.parent.mkdir(parents=True, exist_ok=True)
    pdf = canvas.Canvas(str(output), pagesize=A4)
    width, height = A4
    margin = 45
    passed = result.get("accepted") is True
    pdf.setTitle("WIMT `.in` live-status device trial - " +
                 ("PASS" if passed else "FAILED") + " - run " + source_run)
    pdf.setAuthor("TrackTrain installed-app evidence; source-reported data only")
    pdf.setFillColor(colors.HexColor("#132238"))
    pdf.rect(0, height - 112, width, 112, fill=1, stroke=0)
    pdf.setFillColor(colors.white)
    pdf.setFont("Helvetica-Bold", 19)
    pdf.drawString(margin, height - 58,
                   "WIMT .in live-status test: " + ("PASSED" if passed else "FAILED"))
    pdf.setFont("Helvetica", 10)
    pdf.drawString(margin, height - 80, "Actual installed-APK emulator captures · not a mock-up")

    lines = [
        "Source: direct request to whereismytrain.in/cache/live_status in the selected WIMT source path.",
        "This endpoint may be server-cached; distinct from the app's comparison-only rendering path.",
        "Not the `.org.in` website or Google's WIMT phone app.",
        "Actions run: " + source_run + " | source SHA: " + source_sha,
        "Verdict: " + str(result.get("verdict", "unknown")),
        "HTTP status visible in app details: " +
            str(result.get("httpStatusVisibleInSourceDetails") or "not available"),
    ]
    evidence = result.get("liveEvidence", {})
    if isinstance(evidence, dict):
        lines += [
            "Response run-date match: " + str(evidence.get("runDateMatched")),
            "Both route-endpoint match: " + str(evidence.get("routeEndpointsMatched")),
            "Fresh upstream stamp: " + str(evidence.get("freshSourceStamp")),
            "Source rows in details: " + str(evidence.get("sourceRowsInDetails")),
            "WIMT-attributed visible table clocks: " +
                str(evidence.get("wimtAttributedVisibleTimeCells")),
        ]
    if passed:
        lines += [
            "Strict installed-app test: fresh run/route-matched source clocks were shown in the selected WIMT table.",
            "These are source-reported times, not independently verified railway actuals or GPS.",
        ]
    else:
        lines += [
            "Strict live-data acceptance FAILED; no working-feed claim is made.",
            str(result.get("reason", "The selected source did not provide qualifying displayed data.")),
            "A selector, HTTP response, timetable, or screenshot alone is not acceptance.",
        ]
    lines += [
        "Included screenshots are unmodified and were captured inside the running installed app.",
        "The verifier checks the app-window hierarchy and PNG integrity; it does not OCR image pixels.",
        "This emulator test does not establish provider permission or owner-phone/Google-app telemetry.",
    ]
    pdf.setFillColor(colors.HexColor("#14283b"))
    pdf.setFont("Helvetica", 10.2)
    y = height - 145
    for line in lines:
        words = line.split()
        current = ""
        for word in words:
            candidate = (current + " " + word).strip()
            if pdf.stringWidth(candidate, "Helvetica", 10.2) > width - 2 * margin:
                pdf.drawString(margin, y, current)
                y -= 16
                current = word
            else:
                current = candidate
        if current:
            pdf.drawString(margin, y, current)
            y -= 18
    pdf.setFillColor(colors.HexColor("#596a7a"))
    pdf.setFont("Helvetica", 8)
    pdf.drawString(margin, 30,
                   "Source run: github.com/tarungittarun/Checktrain02/actions/runs/" + source_run)
    pdf.showPage()

    for label, image in images:
        pdf.setFillColor(colors.HexColor("#132238"))
        pdf.setFont("Helvetica-Bold", 14)
        pdf.drawString(margin, height - 39, label)
        pdf.setFont("Helvetica", 9)
        pdf.drawString(margin, height - 57, "Unmodified in-test app screenshot · Actions run " + source_run)
        iw, ih = ImageReader(str(image)).getSize()
        scale = min((width - 2 * margin) / iw, (height - 135) / ih)
        sw, sh = iw * scale, ih * scale
        pdf.drawImage(str(image), (width - sw) / 2, height - 76 - sh,
                      sw, sh, preserveAspectRatio=True, mask="auto")
        pdf.setFont("Helvetica", 8)
        pdf.drawString(margin, 30,
                       "WIMT `.in` source report · no independent position or permission claim")
        pdf.showPage()
    pdf.save()
    return {
        "pdf": str(output), "pages": 1 + len(images), "sourceRun": source_run,
        "accepted": passed, "screenshotVerification": verified,
        "screenshotNames": [path.name for _, path in images],
        "bytes": output.stat().st_size,
    }


def main() -> int:
    if len(sys.argv) != 5:
        raise SystemExit("usage: build_wimt_live_pdf.py <evidence-dir> <output.pdf> <run-id> <40-char-sha>")
    try:
        result = make_pdf(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], sys.argv[4])
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"WIMT_LIVE_PDF_NOT_BUILT: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
