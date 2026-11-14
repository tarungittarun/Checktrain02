#!/usr/bin/env python3
"""Fail closed on home-screen, wrong-run, or unselected-source device evidence."""
from pathlib import Path
import json
import re
import tempfile
import sys
import xml.etree.ElementTree as ET

PNG = b"\x89PNG\r\n\x1a\n"
NAMES = ("wimt-before-selected-source", "wimt-after-selected-source")
EXPECTED_TRAIN = "16227"
EXPECTED_ROUTE = "MYS → TLGP"
EXPECTED_BEFORE_SOURCE = "Selected NTES · official"
EXPECTED_AFTER_SOURCE = "Selected Where Is My Train · website"


def _nodes_for(app_nodes, suffix: str):
    return [node for node in app_nodes
            if node.get("resource-id", "").endswith(":id/" + suffix)]


def verify(folder: Path, require_http_403: bool = False) -> dict:
    screens = folder / "screens"
    details = {}
    run_dates = {}
    for name in NAMES:
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
        run_dates[name] = run_date

        selectors = _nodes_for(app_nodes, "sourceSpinner")
        selected_source = next((node.get("content-desc", "") for node in selectors
                                if node.get("content-desc", "")), "")
        expected_source = EXPECTED_BEFORE_SOURCE if name == NAMES[0] else EXPECTED_AFTER_SOURCE
        if expected_source not in selected_source:
            raise ValueError(f"wrong selected source in {name}: expected {expected_source!r}")

        if name.startswith("wimt-after") and require_http_403 and not any(
                re.search(r"HTTP (?:403|429)", node.get("text", "") + node.get("content-desc", ""))
                for node in app_nodes):
            raise ValueError("after-source hierarchy did not display HTTP 403/429")
        details[name] = {"pngBytes": png.stat().st_size, "appNodes": len(app_nodes),
                         "trainNo": EXPECTED_TRAIN, "route": route,
                         "selectedOriginDate": run_date,
                         "selectedSource": "NTES · official" if name == NAMES[0]
                         else "Where Is My Train · website"}

    if run_dates[NAMES[0]] != run_dates[NAMES[1]]:
        raise ValueError("before/after screenshots do not show the same selected origin date")
    return {"verifiedAppScreens": details,
            "sameSelectedOriginDate": True,
            "selectedWebsiteRequired": True,
            "displayedRefusalRequired": require_http_403}


def _sample_hierarchy(name: str, refusal: bool = False) -> str:
    selected = EXPECTED_BEFORE_SOURCE if name == NAMES[0] else EXPECTED_AFTER_SOURCE
    refusal_node = ('<node package="in.opentrack.india" text="Website refused request (HTTP 403)" />'
                    if refusal else "")
    return ('<hierarchy>'
            '<node package="in.opentrack.india" resource-id="in.opentrack.india:id/trainNo" '
            'text="16227" />'
            '<node package="in.opentrack.india" resource-id="in.opentrack.india:id/routeLabel" '
            'text="MYS → TLGP · identity confirmed live (via erail)" />'
            '<node package="in.opentrack.india" resource-id="in.opentrack.india:id/runDate" '
            'text="run of 12 Nov 2026" />'
            '<node package="in.opentrack.india" resource-id="in.opentrack.india:id/sourceSpinner" '
            'content-desc="' + selected + '. Choose NTES or the Where Is My Train website (.org.in)." />' +
            refusal_node + '</hierarchy>')


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        with tempfile.TemporaryDirectory() as tmp:
            screens = Path(tmp) / "screens"
            screens.mkdir()
            for name in NAMES:
                (screens / (name + ".png")).write_bytes(PNG + b"0" * 2048)
                (screens / (name + ".xml")).write_text(_sample_hierarchy(name))
            report = verify(Path(tmp))
            assert len(report["verifiedAppScreens"]) == 2
            assert report["sameSelectedOriginDate"] is True
            assert report["selectedWebsiteRequired"] is True
            assert report["displayedRefusalRequired"] is False
            (screens / (NAMES[1] + ".xml")).write_text(_sample_hierarchy(NAMES[1], refusal=True))
            assert len(verify(Path(tmp), require_http_403=True)["verifiedAppScreens"]) == 2
            (screens / (NAMES[1] + ".xml")).write_text(
                _sample_hierarchy(NAMES[1]).replace(EXPECTED_AFTER_SOURCE, EXPECTED_BEFORE_SOURCE))
            try:
                verify(Path(tmp))
                raise AssertionError("a still-selected NTES screen was accepted as a website selection")
            except ValueError:
                pass
            (screens / (NAMES[1] + ".xml")).write_text(
                _sample_hierarchy(NAMES[1]).replace("run of 12 Nov 2026", "run of 13 Nov 2026"))
            try:
                verify(Path(tmp))
                raise AssertionError("different origin dates were accepted")
            except ValueError:
                pass
            (screens / (NAMES[1] + ".xml")).write_text(
                '<hierarchy><node package="com.google.android.apps.nexuslauncher" /></hierarchy>')
            try:
                verify(Path(tmp))
                raise AssertionError("launcher was accepted as in-app screenshot")
            except ValueError:
                pass
        print("in-app WIMT evidence gate verifies the selected train, route, date, and website")
    elif len(sys.argv) in (2, 3) and (len(sys.argv) == 2 or
                                      sys.argv[2] in ("--accepted", "--refusal-required")):
        try:
            require_refusal = len(sys.argv) == 3 and sys.argv[2] == "--refusal-required"
            print(json.dumps(verify(Path(sys.argv[1]),
                                    require_http_403=require_refusal), sort_keys=True))
        except (ValueError, OSError, ET.ParseError) as exc:
            print(f"WIMT_APP_SCREENSHOT_NOT_VERIFIED: {exc}", file=sys.stderr)
            sys.exit(1)
    else:
        raise SystemExit("usage: verify_wimt_screens.py <evidence-directory> [--accepted|--refusal-required] | --self-test")
