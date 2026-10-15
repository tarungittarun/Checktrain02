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
import os
import pathlib
import re
import sys

KEYS = ("row", "desc", "boot", "variant", "tests", "all", "online", "offline", "coverage")


def parse(path: pathlib.Path) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


# Keys that describe the row rather than one instrumented pass.
VERDICT = re.compile(r"(ok|failed|skipped|not-run|timeout|crash|no test summary)")

META = {"row", "desc", "boot", "variant", "coverage", "display", "tests", "monkey", "focus",
        "variant_row", "accel", "kvm", "boot_rc", "boot_seconds", "avd"}


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/rows")
    files = sorted(root.glob("*/status.txt"))
    rows = [parse(f) for f in files]
    if not rows:
        print("::error::no e2e row reported anything - the matrix did not run")
        return 1
    groups = ("station0", "station1", "train", "track0", "track1", "track2", "settings")
    expected = {f"api34-{group}" for group in groups}
    if os.environ.get("E2E_FULL") == "true":
        expected |= {f"{device}-{group}" for device in ("min24", "density") for group in groups}
        expected.add("release")
    by_name = {r.get("row") for r in rows}
    missing = expected - by_name
    if missing:
        print(f"::error::device partitions missing entirely: {', '.join(sorted(missing))}")
        return 1

    # Every other key in the status file is one instrumented pass (`TrackingFlowTest=passed`,
    # `online=failed (...)`). The roll-up has to read them all: a harness that only knows about a
    # single `all` pass would report a green row for a run that never executed the classes it names.
    def tests_of(r: dict[str, str]) -> str:
        # A key only counts as an instrumented pass if its value reads like a verdict. That is the durable
        # rule: "1/1 passes green" on a row that never had a device came from a metadata key (`variant_row`)
        # being read as a test, which is exactly the kind of false confidence this file exists to prevent.
        passes = {k: v for k, v in r.items() if k not in META and VERDICT.match(v.strip())}
        if not passes:
            return "failed: no instrumented pass reported"
        bad = sorted(k for k, v in passes.items()
                     if any(word in v for word in ("failed", "not-run", "skipped", "timeout")))
        line = f"{len(passes) - len(bad)}/{len(passes)} passes green"
        if bad:
            # The word "failed" must survive into this string: the gates below search for it.
            line += "; failed: " + ", ".join(bad)
        return line

    print("| row | boot | tests | variant | coverage |")
    print("| --- | --- | --- | --- | --- |")
    for r in rows:
        print(f"| {r.get('row', '?')} | {r.get('boot', '?')} | {tests_of(r)} | "
              f"{r.get('variant', '-')} | {r.get('coverage', '-')} |")

    table = root / "table.md"
    table.write_text("\n".join(
        f"| {r.get('row', '?')} | {r.get('boot', '?')} | {tests_of(r)} | "
        f"{r.get('coverage', '-')} |" for r in rows) + "\n", encoding="utf-8")

    # `boot` carries the wall clock now ("ok 214s"), so this is a prefix test, not an equality.
    booted = [r for r in rows if (r.get("boot") or "").startswith("ok")]
    if not booted:
        print("::error::no emulator booted on any runner for this commit, so the flow suites did not run. "
              "The boot log in each \`e2e-result-*\` artifact says which stage gave up (image download, AVD "
              "creation, acceleration, or the framework never reporting boot_completed). GitHub's ubuntu "
              "runners do expose /dev/kvm, so the usual causes are an image that cannot be fetched, a boot "
              "that needs more heap than the row allowed (MEMORY / BOOT_TIMEOUT are env-overridable), or a "
              "self-hosted runner without kvm.")
        return 1
    print(f"{len(booted)}/{len(rows)} rows booted an emulator and drove the APK.")

    failed = [r for r in booted if "failed" in tests_of(r) or "failed" in r.get("coverage", "")]
    for r in failed:
        print(f"::error::{r.get('row')}: the flow suite failed on a booted emulator")
    if failed:
        return 1
    skipped = [r.get("row", "?") for r in rows if not (r.get("boot") or "").startswith("ok")]
    if skipped:
        print(f"::error::device partitions did not boot: {', '.join(skipped)}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
