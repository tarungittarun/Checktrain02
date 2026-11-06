#!/usr/bin/env python3
"""PDF from an already captured Android emulator artifact. No provider request.

The PDF is deliberately a failed-source report. No mock image, Google-app identity,
secret/cookie/CDN-ID, or uncaptured train position is allowed into its prose.
"""
from pathlib import Path
import json
import sys
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas



def screenshot(folder: Path, name: str) -> Path:
    candidates = [p for p in folder.rglob(name + ".png") if p.is_file() and p.stat().st_size > 0]
    if len(candidates) != 1:
        raise ValueError(f"expected exactly one real captured {name}.png, found {len(candidates)}")
    return candidates[0]


def make_pdf(folder: Path, output: Path, source_run: str, source_sha: str) -> None:
    original = json.loads((folder / "result.json").read_text())
    instrumentation = (folder / "instrumentation.txt").read_text(errors="replace")
    from wimt_device_report import verdict
    diagnosed = verdict(instrumentation, "", 1)
    if original.get("accepted") or diagnosed["verdict"] != "WEBSITE_REFUSED_UI_DIARY_UNAVAILABLE":
        raise ValueError("this PDF requires this exact failed, UI-confirmed website 403 capture")
    if not source_run.isdigit() or len(source_sha) != 40 or not all(c in "0123456789abcdef" for c in source_sha):
        raise ValueError("run id and exact 40-character commit SHA are required")
    # Require in-test captures AND a matching app UI hierarchy; a host
    # screencap after instrumentation exits is commonly the HOME SCREEN.
    from verify_wimt_screens import verify
    verified = verify(folder)
    images = [("Before selecting website source", screenshot(folder / "screens", "wimt-before-selected-source")),
              ("After selected website displayed HTTP 403", screenshot(folder / "screens", "wimt-after-selected-source"))]
    output.parent.mkdir(parents=True, exist_ok=True)
    pdf = canvas.Canvas(str(output), pagesize=A4)
    pdf.setTitle("TrackTrain WIMT website device trial - HTTP 403 - run " + source_run)
    pdf.setAuthor("TrackTrain CI evidence; independent website, not Google WIMT app")
    width, height = A4
    margin = 45
    pdf.setFillColor(colors.HexColor("#132238"))
    pdf.rect(0, height - 112, width, 112, fill=1, stroke=0)
    pdf.setFillColor(colors.white)
    pdf.setFont("Helvetica-Bold", 19)
    pdf.drawString(margin, height - 58, "WIMT website in-app test: FAILED")
    pdf.setFont("Helvetica", 10)
    pdf.drawString(margin, height - 80, "Actual API 34 emulator capture, not a mock-up or phone-app feed")
    y = height - 145
    lines = [
        "Source: independent whereismytrain.org.in website (not Google's WIMT Android app).",
        "Run: GitHub Actions #" + source_run + "  |  APK/test source: " + source_sha[:12],
        "Result: installed app displayed 'Website refused request (HTTP 403)'.",
        "No qualified website past card or source-attributed station clock was shown.",
        "The strict instrumented assertion FAILED; Android build success is not source success.",
        "Wire-level refusal and GET count were not independently verified in this PDF.",
        "The UI refusal does not establish a qualified train position or station clock.",
        "A separate runner Chrome trial also got 403, but it was not this emulator/phone.",
        "This is a NEW capture. The earlier run 42026847014's artifact was deleted",
        "by this repository's six-hour artifact hygiene policy before PDF publication.",
        "These screenshots MUST NOT be represented as screenshots from that earlier run.",
        "PR #3 stays open; this PDF does not certify live WIMT data or position.",
    ]
    pdf.setFont("Helvetica", 10.5)
    pdf.setFillColor(colors.HexColor("#14283b"))
    for line in lines:
        pdf.drawString(margin, y, line)
        y -= 19
    y -= 17
    pdf.setStrokeColor(colors.HexColor("#d3dce5"))
    pdf.line(margin, y, width - margin, y)
    y -= 25
    pdf.setFont("Helvetica-Bold", 11)
    pdf.drawString(margin, y, "Screenshots included from the same device run:")
    y -= 20
    pdf.setFont("Helvetica", 10)
    for label, _ in images:
        pdf.drawString(margin + 12, y, "- " + label)
        y -= 18
    pdf.setFont("Helvetica", 8)
    pdf.setFillColor(colors.HexColor("#596a7a"))
    pdf.drawString(margin, 38, "Source run: github.com/tarungittarun/Checktrain02/actions/runs/" + source_run)
    pdf.showPage()

    for name, image in images:
        pdf.setFillColor(colors.HexColor("#132238"))
        pdf.setFont("Helvetica-Bold", 14)
        pdf.drawString(margin, height - 39, name)
        pdf.setFont("Helvetica", 9)
        pdf.drawString(margin, height - 57, "Unmodified emulator screenshot from Actions run " + source_run)
        iw, ih = ImageReader(str(image)).getSize()
        scale = min((width - 2 * margin) / iw, (height - 135) / ih)
        sw, sh = iw * scale, ih * scale
        pdf.drawImage(str(image), (width - sw) / 2, height - 76 - sh,
                      sw, sh, preserveAspectRatio=True, mask="auto")
        pdf.setFont("Helvetica", 8)
        pdf.drawString(margin, 30, "Independent website failed; screenshot does not prove current train position.")
        pdf.showPage()
    pdf.save()
    print(json.dumps({"pdf": str(output), "pages": 1 + len(images), "sourceRun": source_run,
                      "appScreenshotVerification": verified,
                      "screenshotNames": [p.name for _, p in images],
                      "bytes": output.stat().st_size}, sort_keys=True))


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit("usage: build_wimt_pdf.py <captured-evidence> <output.pdf> <source-run-id> <40-character-source-sha>")
    make_pdf(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], sys.argv[4])
