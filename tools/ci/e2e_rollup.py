#!/usr/bin/env python3
"""Turn the per-row status files of the e2e matrix into one verdict, and decide whether it is acceptable.

Lives in a file rather than inline in the workflow for a reason that has already bitten this project: a
`python3 - << PY` heredoc inside a YAML block scalar carries the YAML indentation with it, and Python reads
that as one unexpected indent at line 1. The failure looks like a broken pipeline, not a broken string, and
it is invisible until somebody runs the job.

The verdict rule is the part worth reading: a row whose emulator never booted is *reported*, not failed,
because a free GitHub runner has no /dev/kvm and that is infrastructure, not product. But if **no** row
booted, the whole run fails, because a device-test pipeline that silently tested no device is worse than
no device pipeline at all — it is a green light with nothing behind it.
"""
from __future__ import annotations

import glob
import pathlib
import sys

KEYS = ("row", "desc", "boot", "variant", "tests", "all", "online", "offline", "coverage")


def parse(path: pathlib.Path) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/rows")
    files = sorted(root.glob("*/status.txt"))
    rows = [parse(f) for f in files]
    if not rows:
        print("::error::no e2e row reported anything - the matrix did not run")
        return 1

    def tests_of(r: dict[str, str]) -> str:
        parts = [f"{k}={r[k]}" for k in ("all", "online", "offline") if k in r]
        return "; ".join(parts) if parts else r.get("tests", "-")

    print("| row | boot | tests | variant | coverage |")
    print("| --- | --- | --- | --- | --- |")
    for r in rows:
        print(f"| {r.get('row', '?')} | {r.get('boot', '?')} | {tests_of(r)} | "
              f"{r.get('variant', '-')} | {r.get('coverage', '-')} |")

    table = root / "table.md"
    table.write_text("\n".join(
        f"| {r.get('row', '?')} | {r.get('boot', '?')} | {tests_of(r)} | "
        f"{r.get('coverage', '-')} |" for r in rows) + "\n", encoding="utf-8")

    booted = [r for r in rows if r.get("boot") == "ok"]
    if not booted:
        print("::error::no emulator booted on any runner for this commit; the flow suites therefore did not "
              "run. Either the images could not be downloaded or the runner has no /dev/kvm (GitHub-hosted "
              "runners have none): use a self-hosted or KVM-capable runner, or run "
              "`./gradlew :app:connectedDebugAndroidTest` on a machine with an emulator.")
        return 1
    print(f"{len(booted)}/{len(rows)} rows booted an emulator and drove the APK.")

    failed = [r for r in booted if "failed" in tests_of(r) or "failed" in r.get("coverage", "")]
    for r in failed:
        print(f"::error::{r.get('row')}: the flow suite failed on a booted emulator")
    required = [r for r in booted if r.get("row") == "min24"]
    if required and "failed" in tests_of(required[0]):
        print("::error::the minSdk row is the one that must pass without KVM, and it did not")
        return 1
    if failed:
        return 1
    skipped = [r.get("row", "?") for r in rows if r.get("boot") != "ok"]
    if skipped:
        print(f"note: rows that could not boot (acceleration): {', '.join(skipped)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
