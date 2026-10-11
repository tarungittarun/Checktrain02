# COMPONENTS — PoC register (brief §7.1-7.3, §13.1)

Rule: one file, one question, one command that prints PASS/FAIL + reason, one artifact.
Graduation: assertion becomes a real test, or the PoC is deleted — no third state.
Every probe/gate gets its own test, including fallback-from-dead-primary (§7.3).

| component | question | command | verdict | artifact |
|-----------|----------|---------|---------|----------|
| probe-ladder | which sources answer, from where? | `python3 tools/probe.py sandbox` | PASS (tool works); S1+S2 blocked-by-network from sandbox 2026-10-11 | tools/captures/probe-sandbox-*.json (untracked); report: docs/spike-verdicts.md |
| probe-self-test | does the probe ask the question it exists to ask? (§7.3) | `cd tools && python3 test_probe.py` | PASS — 4/4 tests incl. dead-host→blocked and single-timeout→retry-once (2026-10-11, offline) | this row; graduates into CI `checks` job |
