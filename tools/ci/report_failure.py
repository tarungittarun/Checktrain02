#!/usr/bin/env python3
"""CI failure self-reporter (brief 11.2 + 10.8).

Problem: run logs live on results-receiver/blobs hosts that are unreachable from the
dev sandbox (TLS reset), so a red job with unreadable logs is a dead end - this bit
the previous session (L-3) and again on run 38176872386. Fix: the job mails itself.
On failure we write the last lines of every available report/log to ci-failure-report.txt
and push it to the SAME branch from inside the workflow. Next API poll reads the cause;
no log host needed. Never pushes from anything but a failed run (if: failure()).
"""
import glob
import os
import subprocess
import sys


def tail(path, n=60):
    try:
        with open(path, "r", errors="replace") as f:
            lines = f.readlines()
        return "".join(lines[-n:])
    except OSError as e:
        return f"(unreadable: {e})"


def report_path():
    # One file PER JOB so two red jobs in one run don't race on a single filename
    # (run 38177163382: probe pushed first, android's push was rejected non-fast-forward).
    job = os.environ.get("GITHUB_JOB", "job").replace("/", "-")
    return f"ci-failure-report-{job}.txt"


def main():
    path = report_path()
    # Loop guard: if this job's report is already in the tree (pushed by a previous failed
    # run of the same commit), do NOT push another one. The fix commit removes the files
    # (recorded in RUNBOOK). Also: pushes by the default GITHUB_TOKEN never re-trigger
    # workflows, so there is no push cascade either way.
    if os.path.exists(path):
        print(f"{path} already present at this commit; not pushing a duplicate")
        return 0

    out = ["CI FAILURE SELF-REPORT (tools/ci/report_failure.py)",
           f"repo={os.environ.get('GITHUB_REPOSITORY')} run={os.environ.get('GITHUB_RUN_ID')}",
           f"job={os.environ.get('GITHUB_JOB')} sha={os.environ.get('GITHUB_SHA')}", ""]

    gradle_log = os.environ.get("GRADLE_LOG", "/tmp/gradle.log")
    if os.path.exists(gradle_log):
        out.append("===== gradle build log (last 120 lines) =====")
        out.append(tail(gradle_log, 120))

    for extra in filter(None, os.environ.get("EXTRA_REPORT_FILES", "").split(":")):
        out.append(f"===== {extra} =====")
        out.append(tail(extra, 120))

    patterns = [
        "app/build/reports/**/*.txt",
        "app/build/reports/**/*.xml",
        "app/build/outputs/logs/**/*",
    ]
    found = False
    for pat in patterns:
        for p in sorted(glob.glob(pat, recursive=True))[:10]:
            found = True
            out.append(f"===== {p} =====")
            out.append(tail(p))
    if not found:
        out.append("(no gradle report files found — failure likely before compilation reports)")

    with open(path, "w") as f:
        f.write("\n".join(out) + "\n")
    print("".join(out))

    # Push to the branch that failed. The checkout token has write scope on push events.
    branch = os.environ.get("GITHUB_REF_NAME")
    if not branch:
        print("no GITHUB_REF_NAME; skipping push", file=sys.stderr)
        return 0
    env = dict(os.environ)
    subprocess.run(["git", "config", "user.name", "ci-failure-reporter"], check=False)
    subprocess.run(["git", "config", "user.email", "ci@opentrack.local"], check=False)
    subprocess.run(["git", "add", path], check=True)
    r = subprocess.run(["git", "commit", "-m", f"ci-failure-report ({os.environ.get('GITHUB_JOB')}) for run {os.environ.get('GITHUB_RUN_ID')} (automated, diagnose-only)"], check=False)
    if r.returncode != 0:
        print("nothing to commit; skipping push")
        return 0
    for attempt in (1, 2):
        r = subprocess.run(["git", "push", "origin", f"HEAD:refs/heads/{branch}"], env=env, check=False)
        if r.returncode == 0:
            print("failure report pushed to branch " + branch)
            return 0
        # A sibling job may have pushed its own report first: rebase onto it and retry once.
        subprocess.run(["git", "pull", "--rebase", "origin", branch], env=env, check=False)
    print("push of failure report failed after one rebase retry", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
