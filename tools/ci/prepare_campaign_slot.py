#!/usr/bin/env python3
"""Prepare one fixed matrix slot without inventing or reusing a train assignment."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def prepare(roster_path: Path, prepare_result: str, slot: int, evidence_dir: Path) -> bool:
    assignment_path = evidence_dir / "assignment.json"
    result_path = evidence_dir / "slot-result.json"
    if prepare_result != "success":
        write_json(result_path, {
            "schemaVersion": 1,
            "slot": slot,
            "status": "PREPARATION_FAILED",
            "trainNo": None,
            "reason": f"roster/APK preparation job result was {prepare_result}",
        })
        return False
    try:
        roster = json.loads(roster_path.read_text(encoding="utf-8"))
        assignments = roster.get("assignments")
        if not isinstance(assignments, list):
            raise ValueError("assignments is not a list")
        seen_slots: set[int] = set()
        seen_trains: set[str] = set()
        for item in assignments:
            if not isinstance(item, dict):
                raise ValueError("assignment is not an object")
            item_slot = int(item["slot"])
            train = str(item["trainNo"])
            if item_slot in seen_slots:
                raise ValueError("duplicate assignment slot")
            if train in seen_trains:
                raise ValueError("duplicate train number")
            if not train.isdigit() or not 4 <= len(train) <= 5:
                raise ValueError("invalid train number")
            if not 1 <= item_slot <= 20:
                raise ValueError("assignment slot outside 1..20")
            seen_slots.add(item_slot)
            seen_trains.add(train)
        assignment = next((item for item in assignments if int(item["slot"]) == slot), None)
    except Exception as error:
        write_json(result_path, {
            "schemaVersion": 1,
            "slot": slot,
            "status": "ROSTER_INVALID_OR_UNAVAILABLE",
            "trainNo": None,
            "reason": type(error).__name__ + ": " + str(error)[:180],
        })
        return False

    if assignment is None:
        write_json(result_path, {
            "schemaVersion": 1,
            "slot": slot,
            "status": "NO_ELIGIBLE_CANDIDATE",
            "trainNo": None,
            "reason": (
                f"preflight found {roster.get('eligibleCount', len(assignments))} eligible distinct "
                "train(s); no assignment was available for this slot"
            ),
            "rosterStatus": roster.get("status", "unknown"),
        })
        return False

    write_json(assignment_path, assignment)
    write_json(result_path, {
        "schemaVersion": 1,
        "slot": slot,
        "status": "ASSIGNED_NOT_STARTED",
        "trainNo": assignment["trainNo"],
        "runDate": assignment.get("runDate"),
        "reason": "distinct preflight assignment ready for eligibility recheck and emulator run",
    })
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--roster", type=Path, required=True)
    parser.add_argument("--prepare-result", required=True)
    parser.add_argument("--slot", type=int, choices=range(1, 21), required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    args = parser.parse_args()
    should_run = prepare(args.roster, args.prepare_result, args.slot, args.evidence_dir)
    output = __import__("os").environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(f"run_emulator={'true' if should_run else 'false'}\n")
            stream.write(f"evidence_dir={args.evidence_dir.as_posix()}\n")
    print(json.dumps({"slot": args.slot, "runEmulator": should_run}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
