# STATUS — tracker (brief §13.3)

Rules bound every commit: resume at first DOING else first NEXT; DONE without an
artifact NAME is still DOING; BLOCKED/HELD must name the exact unblock command or
single owner action; at most ONE open question to the owner, answerable in one word.

| id | task | state | verified-by | next action | needs-device | owner-action |
|----|------|-------|-------------|-------------|--------------|--------------|
| A1 | Audit: repo, toolchain, egress, CI state (§15.1) | DONE:2026-10-11 | docs/LEARNINGS.md#L-1..L-3 (measured output quoted) | none | no | none |
| D1 | docs/ skeleton (§15.2) | DONE:2026-10-11 | this commit; file list in docs/PLAN.md | complete KICKOFF.md verbatim pass (§10-15) | no | none |
| P1 | Local JVM toolchain | BLOCKED:no-JDK-route-in-sandbox | docs/LEARNINGS.md#L-2 (4 routes tested, exact errors) | JVM proof moves to CI per §9.6; unblock = none available locally | no | none |
| M0-1 | Source probe tool, sandbox run (§15.3) | DONE:2026-10-11 | docs/spike-verdicts.md + tools/captures/probe-sandbox-*.json (untracked) | rerun from CI once workflows land; add tools/test_probe.py (PoC-of-PoC, §7.3) | no | none |
| CI-1 | .github/workflows + minimal Android skeleton + first CI-built APK | DOING | run 38176872386: checks SUCCESS; android FAILED at assemble (~40 s); probe FAILED; logs unreadable (L-3 root cause found: log host outside egress) | reporter round: read ci-failure-report.txt the jobs push on failure | no | none |
| P1-fix | .pyc removed from git + __pycache__ ignored (defect of c5cfec1, owned per 14.5) | DONE:2026-10-11 | this commit: git rm --cached + .gitignore line | none | no | none |
| M1 | Contract types + provenance + known-truths gate (§15.4) | HELD:waits-on-CI-1 | - | - | no | none |
| M0-2 | S1/S2 verdicts from owner's phone network | HELD:waits-on-M2-APK | - | after first installable APK: one search, paste exported text (§14.3) | yes | one paste, later |
| M2 | Live pair search | HELD:waits-on-M1 | - | - | yes (exit test) | one search |

## Metrics (§13.6)
- defects found before/after CI this round: 0 / 0
- UNPROVEN traceability rows: 0 shipped, 0 total (nothing shipped yet)
- pipeline wall-clock: n/a (no run on this branch yet)
- first-result latency: n/a
- data-trust verdict: n/a (M1 not started)

## HANDOFF — 2026-10-11 (session: Checktrain03 kickoff, §13.5)
- Milestone state: M0 in progress. Audit + docs skeleton + probe tool DONE and measured.
- Artifact ids: docs/spike-verdicts.md; tools/captures/probe-sandbox-*.json (local only).
- Resume line: start at row CI-1 (workflows + minimal Android skeleton, single push, then READ the run).
- Unresolved blockers:
  - P1 local JDK: no unblock command exists in this sandbox; do not retry the four failed
    routes (LEARNINGS L-2). CI is the JVM verifier.
  - Prev-session APK failure cause UNKNOWN: log download EOF twice (LEARNINGS L-3);
    do NOT re-run that fetch; rebuild fresh evidence in CI-1.
- Rules not to re-litigate: no key, no backend, no fabricated values (§3); one push per
  round (§9.7); CI artifacts short-lived; captures stay out of git (§9.8).
- Owner question count: 0 open.
