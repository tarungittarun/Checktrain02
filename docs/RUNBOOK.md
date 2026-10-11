# RUNBOOK (brief §11.5, keep to ~10 lines)
1. Install: open the `latest-apk` release link on the phone, download app-debug.apk, tap to install (allow unknown sources once).
2. Roll back: install the previous version tag's APK asset the same way.
3. Log export: not built yet (diagnostics are opt-in and write nothing while disabled, §3.6).
4. Weekly report: docs/reports/<year>-week<NN>.md — one score table per source.
5. If ci-failure-report.txt exists in the tree: it was pushed by a red CI run; the FIX commit must delete it (loop guard depends on that).
