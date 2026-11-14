#!/usr/bin/env python3
"""Parallel APK acceptance partitions and mandatory per-traversal evidence.

The same plan drives the runner's selector, exact test count, screenshot checkpoints
and CI self-check. Every @Test belongs to exactly one partition; every method must
own unique mandatory PNG/XML capture tags. Adding a method or duplicating a tag fails
closed instead of silently reducing coverage or overwriting evidence.
"""
from pathlib import Path
import re
import sys

GROUPS = {
    "journey": ("ticketToTimetableHasRealRowsAndClearlyQualifiedPosition",
                "settingsRoundTripReturnsToUsableSearch"),
    "trust": ("invalidOrImpossibleTripCannotMasqueradeAsAResult",
              "primaryControlsHaveLegibleTouchTargets"),
    "refresh": ("manualRefreshKeepsTicketAndShowsAnAnswer",
                "batteryRefreshIsUnmistakableAndTicketSurvivesModeSwitch"),
    "alerts": ("arrivalAlertSetupShowsTriggersPreferencesAndSaveCancel",),
}
CLASS = "in.opentrack.india.e2e.TravellerAcceptanceTest"
SOURCE = Path(__file__).resolve().parents[2] / "app/src/androidTest/java/in/opentrack/india/e2e/TravellerAcceptanceTest.kt"
TEST_METHOD = re.compile(r"@Test\s+fun\s+(\w+)\s*\(")
# openAlertChoices(tag) delegates to E2E.shotRequired(tag); plain shot() and
# diagnostic-only captures deliberately cannot satisfy the mandatory evidence gate.
REQUIRED_CAPTURE = re.compile(
    r'(?:E2E\.shotRequired|openAlertChoices)\("([A-Za-z0-9._-]+)"\)'
)


def test_methods_and_captures(source: str | None = None):
    text = SOURCE.read_text(encoding="utf-8") if source is None else source
    matches = list(TEST_METHOD.finditer(text))
    methods = [match.group(1) for match in matches]
    captures: dict[str, tuple[str, ...]] = {}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        body = text[match.end():end]
        captures[match.group(1)] = tuple(REQUIRED_CAPTURE.findall(body))
    return methods, captures


def check():
    methods, captures = test_methods_and_captures()
    assigned = [name for names in GROUPS.values() for name in names]
    if len(set(assigned)) != len(assigned) or set(methods) != set(assigned):
        raise ValueError(f"Acceptance partitions do not cover each method exactly once: "
                         f"source={sorted(methods)}, groups={sorted(assigned)}")
    if len(methods) < 5 or any(not names or len(names) > 2 for names in GROUPS.values()):
        raise ValueError("Acceptance coverage or per-job work budget changed unexpectedly")
    missing = sorted(name for name in methods if not captures.get(name))
    if missing:
        raise ValueError(f"Acceptance methods lack mandatory screenshots: {missing}")
    owners: dict[str, str] = {}
    duplicates = []
    for method, tags in captures.items():
        if len(tags) != len(set(tags)):
            duplicates.extend(tag for tag in tags if tags.count(tag) > 1)
        for tag in tags:
            previous = owners.get(tag)
            if previous is not None and previous != method:
                duplicates.append(tag)
            owners[tag] = method
    if duplicates:
        raise ValueError(f"Mandatory screenshot tags must be unique: {sorted(set(duplicates))}")
    return len(methods)


def screenshot_tags(group: str) -> list[str]:
    check()
    methods, captures = test_methods_and_captures()
    del methods
    return [tag for method in GROUPS[group] for tag in captures[method]]


def main():
    if len(sys.argv) == 2 and sys.argv[1] == "--check":
        count = check()
        print(f"PASS: {count} end-user methods assigned exactly once across {len(GROUPS)} jobs/profile; "
              "all methods have unique required screenshot checkpoints")
        for group in GROUPS:
            print(f"{group}: {len(screenshot_tags(group))}")
        return
    if len(sys.argv) != 3 or sys.argv[1] not in GROUPS or \
            sys.argv[2] not in ("selector", "count", "screenshots"):
        raise SystemExit("usage: acceptance_plan.py {journey|trust|refresh|alerts} "
                         "{selector|count|screenshots} | --check")
    check()
    group, mode = sys.argv[1:]
    names = GROUPS[group]
    if mode == "selector":
        print(",".join(f"{CLASS}#{method}" for method in names))
    elif mode == "count":
        print(len(names))
    else:
        print("\n".join(screenshot_tags(group)))


if __name__ == "__main__":
    main()
