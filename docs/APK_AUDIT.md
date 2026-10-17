# APK quality audit (full sweep)

A green compiler or fixture suite does **not** certify a download. On a full E2E sweep (`[e2e-full]` on a pushed commit), `e2e.yml` calls `apk-audit.yml` **after its device matrix and coverage report, even if either failed**. This avoids running a second 31-emulator matrix just to summarize a failed first one. The audit is a separate reusable workflow/job; ordinary pushes remain faster.

The same-commit report is in the **APK, source, UI, visual, device and timing audit** job summary, the `apk-quality-audit` artifact, and a comment on that SHA. `tools/ci/apk_audit.py` and `tools/ci/test_apk_audit.py` implement and self-test the verdict. The audit has a three-minute job deadline; a collection timeout/missing artifact is red, never a presumed pass.

| Gate | Required evidence | If missing or wrong |
| --- | --- | --- |
| Release integrity | GitHub rolling-release bytes have the *same SHA-256* as the Android job's APK artifact; release notes identify this exact commit | Fail, not an older APK |
| APK safety and data | ZIP CRC, compiled DEX, package/version, 1–20 MB size, packaged offline timetable, decoded manifest permissions matching source, signature matching the stable **test-only** update key | Fail; no unsigned/uninspectable APK is approved |
| Binary actually tested | Compare compiled DEX hashes in the Android APK against the E2E workflow's installed APK (independent builds) | Fail if different: device testing cannot certify a different binary |
| Backend and source contracts | Four independently run JUnit partitions, ≥300 cases overall, ≥30 source cases, no failures/skips or missing XML. Upstream parser fixtures are not live telemetry. | Fail with failing class/method and assertion |
| Frontend and visual | ≥20 screen-render tests at narrow/large font, station-clock geometry, source wording, screenshot artifact, UI-logic tests | Fail with exact assertion/row |
| Installed-device UX | All 31 required rows across API 24, API 34, density, settings/network/offline, train/track/station searches and R8 release smoke; coverage report passes its existing 45% device gate | Fail with the particular row's step and instrumented assertion |
| Performance | *Observed* job and workflow wall clocks, 180 seconds inclusive, with queue-inclusive workflow wall; sorted table of the longest | Fail and name every breach; no timeout extension hides it |

The report also says **UNVERIFIED** for parity with Google's Where Is My Train phone app: no authenticated phone-app position API has been connected. Website timestamps, web-cache candidates, and an unchanged NTES stop never count as movement.

The full audit being red does not necessarily mean every feature failed: the report separates passed contracts from failed device rows, release integrity, skipped coverage, and infrastructure/performance. Do not merge the open PR on an audit pass alone; the owner must still confirm the updated signed APK on their phone.
