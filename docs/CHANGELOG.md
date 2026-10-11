# CHANGELOG — user-facing, per published artifact (brief §11.6)

## latest-apk @ commit 203e025 (published 2026-10-11T21:52:55Z, size 796,442 B — read from release API)
- What changes on screen: one honest screen. It says "Offline - could not look yet" and
  explains no data is guessed. No train data yet; no live claims; no lies.
- What CI proved: run 38178052660 green — static gates, probe self-tests, assembleDebug,
  size report, publish. Probe from CI: S1 blocked (timeout x2), S2 erail USABLE.
- What is still broken: nothing user-visible; the app is intentionally a skeleton (CI-1).
