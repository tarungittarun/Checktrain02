#!/usr/bin/env python3
"""Build a PDF only from real, verified in-test screenshots of the installed APK."""
from pathlib import Path
import json
import sys
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas


def make_pdf(folder: Path, output: Path, source_run: str, source_sha: str) -> dict:
    result = json.loads((folder / "result.json").read_text())
    if result.get("inAppScreensVerified") is not True:
        raise ValueError("no verified in-app screenshots; refusing to make a PDF")
    if not source_run.isdigit() or len(source_sha) != 40 or \
            not all(c in "0123456789abcdef" for c in source_sha):
        raise ValueError("run ID and exact 40-character source SHA are required")
    from verify_wimt_cache_screens import REQUIRED, verify
    verified = verify(folder, accepted=result.get("accepted") is True)
    screenshots = [(label, folder / "screens" / f"{name}.png")
                   for label, name in REQUIRED.items()]
    output.parent.mkdir(parents=True, exist_ok=True)
    pdf = canvas.Canvas(str(output), pagesize=A4)
    passed = result.get("accepted") is True
    width, height = A4
    margin = 45
    pdf.setTitle("WIMT web-cache device trial - " + ("PASS" if passed else "FAILED") +
                 " - run " + source_run)
    pdf.setAuthor("TrackTrain CI evidence; undocumented web cache, not Google WIMT app")
    pdf.setFillColor(colors.HexColor("#132238"))
    pdf.rect(0, height - 112, width, 112, fill=1, stroke=0)
    pdf.setFillColor(colors.white)
    pdf.setFont("Helvetica-Bold", 19)
    pdf.drawString(margin, height - 58,
                   "WIMT web-cache test: " + ("PASSED" if passed else "FAILED"))
    pdf.setFont("Helvetica", 10)
    pdf.drawString(margin, height - 80,
                   "Actual installed-APK emulator capture · not a mock-up")
    y = height - 145
    lines = [
        "Source: whereismytrain.in/cache/live_status, an undocumented web cache.",
        "Not whereismytrain.org.in and not Google's Where Is My Train Android app.",
        "Run: GitHub Actions #" + source_run + " | source SHA: " + source_sha,
        "Verdict: " + str(result.get("verdict", "unknown")),
    ]
    rows = result.get("candidateRows")
    if passed:
        lines += [
            "The strict installed-APK test displayed " + str(rows) +
            " dated source-reported row(s) in the comparison details.",
            "Rows remained unverified; they were not presented as railway ACTUAL or GPS.",
        ]
    else:
        lines += [
            "Strict cache-data acceptance FAILED; no working WIMT feed is claimed.",
            str(result.get("reason", "The source did not supply qualifying rows.")),
            "A selected source, HTTP answer, timetable, or screenshot alone is not acceptance.",
        ]
    lines += [
        "Captured screenshots are unmodified and were taken inside the installed app during this test.",
        "This evidence does not establish provider permission or Google phone-app telemetry.",
    ]
    pdf.setFont("Helvetica", 10.2)
    pdf.setFillColor(colors.HexColor("#14283b"))
    for line in lines:
        # Wrap long diagnostic lines without shrinking the type to unreadable text.
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
    for label, image in screenshots:
        pdf.setFillColor(colors.HexColor("#132238"))
        pdf.setFont("Helvetica-Bold", 14)
        pdf.drawString(margin, height - 39, "Installed app · " + label)
        pdf.setFont("Helvetica", 9)
        pdf.drawString(margin, height - 57, "Unmodified in-test emulator screenshot · run " + source_run)
        iw, ih = ImageReader(str(image)).getSize()
        scale = min((width - 2 * margin) / iw, (height - 135) / ih)
        sw, sh = iw * scale, ih * scale
        pdf.drawImage(str(image), (width - sw) / 2, height - 76 - sh,
                      sw, sh, preserveAspectRatio=True, mask="auto")
        pdf.setFont("Helvetica", 8)
        pdf.drawString(margin, 30,
                       "Undocumented web-cache claim; not independently verified train position.")
        pdf.showPage()
    pdf.save()
    return {
        "pdf": str(output), "pages": 1 + len(screenshots), "sourceRun": source_run,
        "accepted": passed, "screenshotVerification": verified,
        "screenshotNames": [path.name for _, path in screenshots],
        "bytes": output.stat().st_size,
    }


def main() -> int:
    if len(sys.argv) != 5:
        raise SystemExit("usage: build_wimt_cache_pdf.py <evidence-dir> <output.pdf> <run-id> <40-char-sha>")
    try:
        data = make_pdf(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], sys.argv[4])
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"WIMT_CACHE_PDF_NOT_BUILT: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(data, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
