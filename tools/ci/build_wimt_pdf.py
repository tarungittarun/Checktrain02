#!/usr/bin/env python3
"""PDF from actual in-app emulator captures. No provider request or fabricated evidence.

A dated route-matched source claim may be labelled PASS; refusals and every other
source outcome are diagnostics only. No Google-app identity, secret/cookie/CDN-ID,
or uncaptured train position is added to the PDF.
"""
from pathlib import Path
import datetime as dt
import json
import sys

EXPECTED_TRAIN = "16227"
EPOCH = dt.date(1970, 1, 1)
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))


def _same_origin_date(original: dict) -> bool:
    day = original.get("originDay")
    date = original.get("originRunDateIst")
    if type(day) is not int or day < 0 or not isinstance(date, str):
        return False
    try:
        expected = (EPOCH + dt.timedelta(days=day)).isoformat()
        parsed = dt.date.fromisoformat(date)
    except (OverflowError, ValueError):
        return False
    return date == expected == parsed.isoformat()


def _event_freshness_complete(original: dict) -> bool:
    try:
        claim = dt.datetime.fromisoformat(original["siteClaimTimeIst"])
        published = dt.datetime.fromisoformat(original["pagePublishedAtIst"])
        checked = dt.datetime.fromisoformat(original["checkedAtIst"])
        claim_age = original["siteClaimAgeSecondsAtCheck"]
        page_age = original["pageAgeSecondsAtCheck"]
        if claim.tzinfo is None or published.tzinfo is None or checked.tzinfo is None:
            return False
        claim, published, checked = (value.astimezone(IST) for value in
                                     (claim, published, checked))
        if not (claim <= published <= checked):
            return False
        return (type(claim_age) is int and claim_age >= 0 and
                type(page_age) is int and 0 <= page_age <= 300 and
                abs(int((checked - claim).total_seconds()) - claim_age) <= 1 and
                abs(int((checked - published).total_seconds()) - page_age) <= 1)
    except (KeyError, TypeError, ValueError, OverflowError):
        return False


def accepted_result(original: dict) -> bool:
    site_route = original.get("siteRoute")
    site_rows = original.get("siteRows")
    containers = original.get("siteCardContainers")
    gets = original.get("websiteGetCount")
    return (original.get("accepted") is True and
            original.get("instrumentationPassed") is True and
            original.get("websiteAnswered") is True and
            original.get("websiteRefused") is False and
            original.get("wireReceiptAvailable") is True and
            type(gets) is int and gets > 0 and
            original.get("source") == "whereismytrain.org.in" and
            original.get("trainNumber") == EXPECTED_TRAIN and
            _same_origin_date(original) and
            original.get("sitePageRunDate") == original.get("originRunDateIst") and
            original.get("siteDateMatched") is True and
            type(site_rows) is int and site_rows > 0 and
            original.get("acceptedMode") == "DATED_ROUTE_MATCHED_SITE_EVENT" and
            original.get("runIdentity") == "DATE_ROUTE_MATCHED" and
            site_route is not None and site_route == original.get("selectedRoute") and
            site_route == original.get("sitePageEndpoints") and
            original.get("siteClaimTimeBasis") == "selected_origin_date_verified" and
            original.get("siteClaimKind") in {"ARRIVAL", "DEPARTURE"} and
            isinstance(original.get("siteClaimStation"), str) and
            original["siteClaimStation"].isalnum() and
            1 <= len(original["siteClaimStation"]) <= 6 and
            type(containers) is int and containers > 0 and
            type(original.get("detachedSiteClaims")) is int and
            original["detachedSiteClaims"] == 0 and
            original.get("provenanceComplete") is True and
            original.get("sourceTransport") == "NETWORK_ANSWER" and
            original.get("googlePhoneAppFeed") is False and
            original.get("browserEngineRequest") is True and
            original.get("websiteGetCountScope") == "main_document_navigation_only" and
            _event_freshness_complete(original))


def screenshot(folder: Path, name: str) -> Path:
    candidates = [p for p in folder.rglob(name + ".png") if p.is_file() and p.stat().st_size > 0]
    if len(candidates) != 1:
        raise ValueError(f"expected exactly one real captured {name}.png, found {len(candidates)}")
    return candidates[0]


def make_pdf(folder: Path, output: Path, source_run: str, source_sha: str) -> None:
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.utils import ImageReader
    from reportlab.pdfgen import canvas

    original = json.loads((folder / "result.json").read_text())
    instrumentation = (folder / "instrumentation.txt").read_text(errors="replace")
    from wimt_device_report import verdict
    diagnosed = verdict(instrumentation, "", 1)
    accepted = accepted_result(original)
    # A failed parser run can still be a useful actual-screenshot diagnostic. The
    # PDF always labels that case NOT QUALIFIED; only a source-qualified, explicit
    # date+route pass can receive a PASS label. A refusal needs a matching UI capture.
    refused = not accepted and diagnosed.get("websiteRefused") is True
    status_label = "PASS" if accepted else "REFUSED" if refused else "NOT QUALIFIED"
    if not source_run.isdigit() or len(source_sha) != 40 or not all(c in "0123456789abcdef" for c in source_sha):
        raise ValueError("run id and exact 40-character commit SHA are required")
    # Require in-test captures AND a matching app UI hierarchy; a host
    # screencap after instrumentation exits is commonly the HOME SCREEN.
    from verify_wimt_screens import verify
    verified = verify(folder, require_http_403=refused)
    images = [("Before selecting website source", screenshot(folder / "screens", "wimt-before-selected-source")),
              ("After selected website response", screenshot(folder / "screens", "wimt-after-selected-source"))]
    output.parent.mkdir(parents=True, exist_ok=True)
    pdf = canvas.Canvas(str(output), pagesize=A4)
    pdf.setTitle("TrackTrain independent website device trial - " + status_label + " - run " + source_run)
    pdf.setAuthor("TrackTrain CI evidence; independent website, not Google WIMT app")
    width, height = A4
    margin = 45
    pdf.setFillColor(colors.HexColor("#132238"))
    pdf.rect(0, height - 112, width, 112, fill=1, stroke=0)
    pdf.setFillColor(colors.white)
    pdf.setFont("Helvetica-Bold", 19)
    pdf.drawString(margin, height - 58, "WIMT website in-app test: " + status_label)
    pdf.setFont("Helvetica", 10)
    pdf.drawString(margin, height - 80, "Actual API 34 emulator capture, not a mock-up or phone-app feed")
    y = height - 145
    lines = [
        "Source: independent whereismytrain.org.in website (not Google's WIMT Android app).",
        "Run: GitHub Actions #" + source_run + "  |  APK/test source: " + source_sha[:12],
    ]
    if accepted:
        lines += [
            "Strict installed-APK test: PASS_DATED_ROUTE_MATCHED_SITE_EVENT.",
            "Visible site date, selected train, selected route, and a distinct source-marked station claim matched.",
            "This is still a website claim, not independently measured train movement or GPS.",
        ]
    elif refused:
        lines += [
            "Result: the installed app visibly displayed an HTTP 403/429 website refusal.",
            "No qualified website station claim was shown; the strict instrumented assertion did not pass.",
            "Wire-level refusal and GET count are reported separately and are not inferred from the screenshot.",
        ]
    else:
        reason = str(original.get("verdict", "SITE_REPLY_OR_SOURCE_QUALIFICATION_MISSING"))[:180]
        lines += [
            "Strict status: NOT QUALIFIED — " + reason + ".",
            "No explicit selected-date + route-qualified site event passed the installed-app gate.",
            "A network answer, timetable echo, page clock, or detached route claim is not a pass.",
            "The screenshots document the actual app state; they do not prove train movement or GPS.",
        ]
    lines += [
        "Screenshots were taken during this installed-app test, not from the launcher.",
        "PR #3 stays open; an emulator test is not Google's phone-app telemetry.",
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
        pdf.drawString(margin, 30, "Independent website; screenshot does not prove measured train position.")
        pdf.showPage()
    pdf.save()
    print(json.dumps({"pdf": str(output), "pages": 1 + len(images), "sourceRun": source_run,
                      "sourceAccepted": accepted, "acceptedMode": original.get("acceptedMode", "REFUSAL"),
                      "siteRows": original.get("siteRows", 0),
                      "detachedSiteClaims": original.get("detachedSiteClaims", 0),
                      "appScreenshotVerification": verified,
                      "screenshotNames": [p.name for _, p in images],
                      "bytes": output.stat().st_size}, sort_keys=True))


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit("usage: build_wimt_pdf.py <captured-evidence> <output.pdf> <source-run-id> <40-character-source-sha>")
    make_pdf(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], sys.argv[4])
