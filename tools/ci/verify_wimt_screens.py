#!/usr/bin/env python3
"""Fail closed on home-screen, skipped traversal, wrong run, or unselected-source evidence."""
from pathlib import Path
import datetime as dt
import json
import re
import tempfile
import sys
import xml.etree.ElementTree as ET

PNG = b"\x89PNG\r\n\x1a\n"
SETTINGS = "wimt-settings-diagnostics"
SEARCH_CRITERIA = "wimt-search-route-criteria"
SEARCH_RESULTS = "wimt-search-route-results"
BEFORE = "wimt-before-selected-source"
PICKER = "wimt-source-picker-open"
AFTER = "wimt-after-selected-source"
NAMES = (SETTINGS, SEARCH_CRITERIA, SEARCH_RESULTS, BEFORE, PICKER, AFTER)
TRACK_NAMES = (BEFORE, PICKER, AFTER)
EXPECTED_TRAIN = "16227"
EXPECTED_ROUTE = "MYS → TLGP"
EXPECTED_BEFORE_SOURCE = "Selected NTES · official"
EXPECTED_AFTER_SOURCE = "Selected Where Is My Train · website"
WEBSITE_LABEL = "Where Is My Train · website"


def _nodes_for(app_nodes, suffix: str):
    return [node for node in app_nodes
            if node.get("resource-id", "").endswith(":id/" + suffix)]


def _has_visible_text(app_nodes, text: str) -> bool:
    return any(text in node.get("text", "") for node in app_nodes)


def _screenshot_origin_date(label: str) -> str | None:
    match = re.fullmatch(r"run of (\d{1,2}) ([A-Za-z]{3}) (\d{4})", label.strip(), re.IGNORECASE)
    if not match:
        return None
    months = {name.lower(): number for number, name in enumerate(
        ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"), 1)}
    month = months.get(match.group(2).lower())
    if month is None:
        return None
    try:
        return dt.date(int(match.group(3)), month, int(match.group(1))).isoformat()
    except ValueError:
        return None


def _result_origin_date(folder: Path) -> tuple[bool, str | None]:
    result_path = folder / "result.json"
    if not result_path.exists():
        return False, None
    try:
        result = json.loads(result_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("result.json is not readable JSON") from exc
    value = result.get("originRunDateIst") if isinstance(result, dict) else None
    if not isinstance(value, str):
        return True, None
    try:
        parsed = dt.date.fromisoformat(value)
    except ValueError:
        return True, None
    return True, parsed.isoformat() if parsed.isoformat() == value else None


def _verify_capture(folder: Path, name: str, require_http_403: bool,
                    allow_unselected_website: bool) -> tuple[dict, str | None, str | None, bool]:
    screens = folder / "screens"
    png, xml = screens / (name + ".png"), screens / (name + ".xml")
    if not png.exists() or png.stat().st_size < 1000 or png.read_bytes()[:8] != PNG:
        raise ValueError(f"missing/invalid in-test PNG: {name}")
    if not xml.exists() or xml.stat().st_size < 100:
        raise ValueError(f"missing in-test hierarchy: {name}")
    root = ET.parse(xml).getroot()
    nodes = list(root.iter("node"))
    app_nodes = [node for node in nodes if node.get("package") == "in.opentrack.india"]
    if not app_nodes:
        raise ValueError(f"not the foreground TrackTrain app: {name}")

    selected_source = None
    route = None
    run_date = None
    displayed_refusal = False
    if name == SETTINGS:
        toggles = _nodes_for(app_nodes, "diagToggle")
        states = _nodes_for(app_nodes, "diagState")
        enabled = any(node.get("checked", "").lower() == "true" or
                      node.get("text", "").strip().lower() in {"on", "turn off collection"}
                      for node in toggles)
        enabled = enabled or any(node.get("text", "").strip().startswith("Collecting")
                                 for node in states)
        if not enabled:
            raise ValueError("source diary is not visibly enabled in the Settings capture")
    elif name == SEARCH_CRITERIA:
        if not _nodes_for(app_nodes, "find"):
            raise ValueError("search criteria capture does not show the Find control")
        visible_text = " ".join(node.get("text", "") + " " + node.get("content-desc", "")
                                 for node in app_nodes)
        normalized = re.sub(r"\s+", " ", visible_text)
        if not re.search(r"\bSelected\s+MYS\b", normalized, re.IGNORECASE) or \
                not re.search(r"\bSelected\s+TLGP\b", normalized, re.IGNORECASE):
            raise ValueError("search criteria capture does not show selected MYS and TLGP stations")
    elif name == SEARCH_RESULTS:
        if not any(node.get("text", "").strip() == EXPECTED_TRAIN
                   for node in _nodes_for(app_nodes, "hitNo")):
            raise ValueError(f"search results do not show the pinned train {EXPECTED_TRAIN}")
        if not _nodes_for(app_nodes, "hitGo"):
            raise ValueError("search results do not show a selectable Track action")
    else:
        train = _nodes_for(app_nodes, "trainNo")
        if not train or not any(node.get("text", "").strip() == EXPECTED_TRAIN for node in train):
            raise ValueError(f"expected train {EXPECTED_TRAIN} is not visible: {name}")
        routes = _nodes_for(app_nodes, "routeLabel")
        route = next((node.get("text", "").strip() for node in routes
                      if EXPECTED_ROUTE in node.get("text", "")), None)
        if route is None:
            raise ValueError(f"expected selected route {EXPECTED_ROUTE} is not visible: {name}")
        dates = _nodes_for(app_nodes, "runDate")
        run_date = next((node.get("text", "").strip() for node in dates
                         if node.get("text", "").strip()), None)
        if run_date is None:
            raise ValueError(f"selected origin date is not visible: {name}")

        selectors = _nodes_for(app_nodes, "sourceSpinner")
        selected_source = next((node.get("content-desc", "") for node in selectors
                                if node.get("content-desc", "")), "")
        expected_source = EXPECTED_AFTER_SOURCE if name == AFTER else EXPECTED_BEFORE_SOURCE
        if name == AFTER and allow_unselected_website:
            allowed_sources = (EXPECTED_AFTER_SOURCE, EXPECTED_BEFORE_SOURCE)
        else:
            allowed_sources = (expected_source,)
        if not any(source in selected_source for source in allowed_sources):
            raise ValueError(f"wrong selected source in {name}: expected one of {allowed_sources!r}")
        if name == PICKER and not _has_visible_text(app_nodes, WEBSITE_LABEL):
            raise ValueError("source-picker capture does not show the website option")
        if name == AFTER:
            displayed_refusal = any(
                re.search(r"HTTP (?:403|429)", node.get("text", "") + node.get("content-desc", ""))
                for node in app_nodes)
            if require_http_403 and not displayed_refusal:
                raise ValueError("after-source hierarchy did not display HTTP 403/429")

    detail = {"pngBytes": png.stat().st_size, "appNodes": len(app_nodes)}
    if route is not None:
        detail.update({"trainNo": EXPECTED_TRAIN, "route": route,
                       "selectedOriginDate": run_date})
    if selected_source is not None:
        observed_label = ("Where Is My Train · website" if EXPECTED_AFTER_SOURCE in selected_source
                          else "NTES · official")
        detail.update({"selectedSource": observed_label,
                       "sourcePickerContentDescription": selected_source})
    return detail, run_date, selected_source, displayed_refusal


def verify(folder: Path, require_http_403: bool = False,
           allow_unselected_website: bool = False,
           allow_partial: bool = False) -> dict:
    details = {}
    run_dates = {}
    missing = []
    errors = {}
    displayed_refusal = False
    for name in NAMES:
        try:
            detail, run_date, _selected_source, refused = _verify_capture(
                folder, name, require_http_403, allow_unselected_website)
            details[name] = detail
            if run_date is not None:
                run_dates[name] = run_date
            displayed_refusal = displayed_refusal or refused
        except (ValueError, OSError, ET.ParseError) as exc:
            if not allow_partial:
                raise
            missing.append(name)
            errors[name] = str(exc)[:220]

    if not allow_partial and set(run_dates) != set(TRACK_NAMES):
        raise ValueError("before/picker/after screenshots must all show the selected origin date")
    known_dates = [run_dates[name] for name in TRACK_NAMES if name in run_dates]
    labels_match = len(run_dates) == len(TRACK_NAMES) and len(set(known_dates)) == 1
    if len(set(known_dates)) > 1 and not allow_partial:
        raise ValueError("before/picker/after screenshots do not show the same selected origin date")
    result_present, result_date = _result_origin_date(folder)
    screenshot_date = _screenshot_origin_date(known_dates[0]) if known_dates else None
    result_date_matches = (not result_present or
                           (result_date is not None and screenshot_date == result_date))
    if result_present and result_date is None and not (allow_partial or allow_unselected_website):
        raise ValueError("device result has no valid selected origin date")
    if result_present and result_date is not None and screenshot_date != result_date and \
            not (allow_partial or allow_unselected_website):
        raise ValueError("in-app screenshot origin date does not match the device result")
    same_date = labels_match and result_date_matches
    if not details:
        raise ValueError("no authentic in-test TrackTrain screenshots are available")

    after = details.get(AFTER, {})
    selected_website = after.get("selectedSource") == "Where Is My Train · website"
    return {"verifiedAppScreens": details,
            "sameSelectedOriginDate": same_date,
            "resultOriginDateIst": result_date,
            "screenshotOriginDateIst": screenshot_date,
            "screenshotsMatchResultOriginDate": result_date_matches,
            "selectedWebsiteRequired": True,
            "selectedWebsiteObserved": selected_website,
            "displayedRefusalRequired": require_http_403,
            "displayedRefusalObserved": displayed_refusal,
            "completeTraversal": not missing,
            "missingScreens": missing,
            "captureErrors": errors,
            "verificationMode": "DIAGNOSTIC" if allow_partial or allow_unselected_website else "STRICT"}


def _node(resource: str, text: str = "", content_desc: str = "", checked: str = "") -> str:
    attrs = f'package="in.opentrack.india" resource-id="in.opentrack.india:id/{resource}"'
    if text:
        attrs += f' text="{text}"'
    if content_desc:
        attrs += f' content-desc="{content_desc}"'
    if checked:
        attrs += f' checked="{checked}"'
    return f"<node {attrs} />"


def _sample_hierarchy(name: str, refusal: bool = False) -> str:
    if name == SETTINGS:
        body = _node("diagToggle", text="Turn off collection") + \
            _node("diagState", text="Collecting · 1 bytes")
    elif name == SEARCH_CRITERIA:
        body = _node("fromBox", text="Selected MYS") + _node("toBox", text="Selected TLGP") + \
            _node("find", text="Find")
    elif name == SEARCH_RESULTS:
        body = _node("hitNo", text=EXPECTED_TRAIN) + _node("hitGo", text="Track")
    else:
        before = name != AFTER
        selected = EXPECTED_BEFORE_SOURCE if before else EXPECTED_AFTER_SOURCE
        route = "MYS → TLGP · identity confirmed live (via erail)"
        body = _node("trainNo", text=EXPECTED_TRAIN) + _node("routeLabel", text=route) + \
            _node("runDate", text="run of 12 Nov 2026") + \
            _node("sourceSpinner", content_desc=selected + ". Choose NTES or the website.")
        if name == PICKER:
            body += _node("websiteOption", text="● " + WEBSITE_LABEL + " · no usable stop report")
        if refusal and name == AFTER:
            body += _node("connectionAlert", text="Website refused request (HTTP 403)")
    return "<hierarchy>" + body + "</hierarchy>"


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        with tempfile.TemporaryDirectory() as tmp:
            screens = Path(tmp) / "screens"
            screens.mkdir()
            for name in NAMES:
                (screens / (name + ".png")).write_bytes(PNG + b"0" * 2048)
                (screens / (name + ".xml")).write_text(_sample_hierarchy(name))
            (Path(tmp) / "result.json").write_text(json.dumps({
                "originRunDateIst": "2026-11-12"}))
            report = verify(Path(tmp))
            assert len(report["verifiedAppScreens"]) == len(NAMES)
            assert report["sameSelectedOriginDate"] is True
            assert report["selectedWebsiteRequired"] is True
            assert report["selectedWebsiteObserved"] is True
            assert report["resultOriginDateIst"] == "2026-11-12"
            assert report["screenshotOriginDateIst"] == "2026-11-12"
            assert report["screenshotsMatchResultOriginDate"] is True
            assert report["completeTraversal"] is True
            assert report["verificationMode"] == "STRICT"
            assert report["displayedRefusalRequired"] is False
            (screens / (SETTINGS + ".xml")).write_text(
                _sample_hierarchy(SETTINGS).replace("Turn off collection", "Turn on collection")
                    .replace("Collecting", "Stopped"))
            try:
                verify(Path(tmp))
                raise AssertionError("disabled diagnostic diary was accepted")
            except ValueError:
                pass
            (screens / (SETTINGS + ".xml")).write_text(_sample_hierarchy(SETTINGS))
            (screens / (AFTER + ".xml")).write_text(_sample_hierarchy(AFTER, refusal=True))
            assert len(verify(Path(tmp), require_http_403=True)["verifiedAppScreens"]) == len(NAMES)
            (screens / (AFTER + ".xml")).write_text(
                _sample_hierarchy(AFTER).replace(EXPECTED_AFTER_SOURCE, EXPECTED_BEFORE_SOURCE))
            try:
                verify(Path(tmp))
                raise AssertionError("a still-selected NTES screen was accepted as a website selection")
            except ValueError:
                pass
            diagnostic = verify(Path(tmp), allow_unselected_website=True)
            assert diagnostic["selectedWebsiteRequired"] is True
            assert diagnostic["selectedWebsiteObserved"] is False
            assert diagnostic["verificationMode"] == "DIAGNOSTIC"
            (screens / (AFTER + ".xml")).write_text(_sample_hierarchy(AFTER))
            (screens / (SEARCH_RESULTS + ".png")).unlink()
            partial = verify(Path(tmp), allow_unselected_website=True, allow_partial=True)
            assert partial["completeTraversal"] is False
            assert SEARCH_RESULTS in partial["missingScreens"]
            assert partial["sameSelectedOriginDate"] is True
            (screens / (SEARCH_RESULTS + ".png")).write_bytes(PNG + b"0" * 2048)
            (screens / (AFTER + ".xml")).write_text(
                _sample_hierarchy(AFTER).replace("run of 12 Nov 2026", "run of 13 Nov 2026"))
            try:
                verify(Path(tmp))
                raise AssertionError("different origin dates were accepted")
            except ValueError:
                pass
            partial_mismatch = verify(Path(tmp), allow_unselected_website=True, allow_partial=True)
            assert partial_mismatch["sameSelectedOriginDate"] is False
            (screens / (AFTER + ".xml")).write_text(_sample_hierarchy(AFTER))
            (Path(tmp) / "result.json").write_text(json.dumps({
                "originRunDateIst": "2026-11-13"}))
            try:
                verify(Path(tmp))
                raise AssertionError("screenshots with a different result date were accepted")
            except ValueError:
                pass
            mismatched_result = verify(Path(tmp), allow_unselected_website=True, allow_partial=True)
            assert mismatched_result["sameSelectedOriginDate"] is False
            assert mismatched_result["screenshotsMatchResultOriginDate"] is False
            (Path(tmp) / "result.json").write_text(json.dumps({
                "originRunDateIst": "2026-11-12"}))
            (screens / (PICKER + ".xml")).write_text(
                _sample_hierarchy(PICKER).replace(WEBSITE_LABEL, "unrelated source"))
            try:
                verify(Path(tmp))
                raise AssertionError("source option was absent from the picker capture")
            except ValueError:
                pass
            (screens / (PICKER + ".xml")).write_text(_sample_hierarchy(PICKER))
            (screens / (SEARCH_CRITERIA + ".xml")).write_text(
                _sample_hierarchy(SEARCH_CRITERIA).replace("Selected MYS", "Selected SBC"))
            try:
                verify(Path(tmp))
                raise AssertionError("wrong route criteria were accepted")
            except ValueError:
                pass
            (screens / (SEARCH_CRITERIA + ".xml")).write_text(_sample_hierarchy(SEARCH_CRITERIA))
            (screens / (AFTER + ".xml")).write_text(
                '<hierarchy><node package="com.google.android.apps.nexuslauncher" /></hierarchy>')
            try:
                verify(Path(tmp))
                raise AssertionError("launcher was accepted as in-app screenshot")
            except ValueError:
                pass
        print("strict WIMT acceptance rejects unselected sources; diagnostic mode preserves only actual in-app captures")
    elif len(sys.argv) in (2, 3) and (len(sys.argv) == 2 or
                                      sys.argv[2] in ("--accepted", "--refusal-required", "--diagnostic")):
        try:
            option = sys.argv[2] if len(sys.argv) == 3 else "--accepted"
            require_refusal = option == "--refusal-required"
            diagnostic = option == "--diagnostic"
            print(json.dumps(verify(Path(sys.argv[1]),
                                    require_http_403=require_refusal,
                                    allow_unselected_website=diagnostic,
                                    allow_partial=diagnostic), sort_keys=True))
        except (ValueError, OSError, ET.ParseError) as exc:
            print(f"WIMT_APP_SCREENSHOT_NOT_VERIFIED: {exc}", file=sys.stderr)
            sys.exit(1)
    else:
        raise SystemExit("usage: verify_wimt_screens.py <evidence-directory> [--accepted|--refusal-required|--diagnostic] | --self-test")
