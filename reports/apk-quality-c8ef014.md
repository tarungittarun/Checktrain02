# Published-APK traveller acceptance — c8ef014 / R118

**VERDICT: FAIL. Do not certify or merge this APK.** This report is for commit `c8ef014db8b1414fb45866c83ece282c6a70e81d` and the [Android workflow run 39010955293](https://github.com/tarungittarun/Checktrain02/actions/runs/39010955293). Its published `app-debug.apk` is versionCode 48 / `1.0.47-r118`, 11,082,952 bytes, SHA-256 **`c6d8f286b112128ab989ef6dde8a4f1a1b76bd42fa13640902bd57925658c5c7`**. The download URL is rolling: [latest-apk/app-debug.apk](https://github.com/tarungittarun/Checktrain02/releases/download/latest-apk/app-debug.apk); check the release notes still identify `c8ef014` before associating later downloads with this report.

## What was actually tested

`android.yml` now blocks on two `traveller / published APK` jobs **after** the APK publish and instrumented-test compile. Each job downloads the published APK and the build artifact independently, verifies byte-for-byte equality, ZIP/offline asset/package/version/permissions, verifies the v2 signature against the stable test-only update certificate, installs the **downloaded release** on an API 34 emulator, pulls `base.apk` back from PackageManager, and compares the full bytes again. It then runs five independent finger-and-accessibility-tree journeys. Online uses standard display; offline disables the radio and uses a 1080×2160 / 440 dpi viewport with 1.30× font size. Every failure uploads the instrumentation transcript, screenshots, hierarchy and logcat in `traveller-evidence-online` / `traveller-evidence-offline`, and posts the failing assertion to this commit. This avoids crediting tests from an independently built APK as proof of the downloadable APK.

**Preflight PASS in both profiles:** the published APK, same-run Android artifact and installed `base.apk` all had exactly SHA-256 `c6d8f286…`; APK v2 signature verification and stable test-key certificate comparison passed. Package/version and declared permissions matched policy, accounting only for AndroidX's package-scoped generated receiver permission. The offline timetable asset is packaged. This confirms the exact bytes used in these tests; it does not establish live phone-app position parity or production signing.

| Traveller journey | Online | Offline / large text | Evidence and limitation |
| --- | --- | --- | --- |
| Ticket number → exact train → route, date, visible schedule and qualified position | PASS | **FAIL** | Offline large-text viewport had **fewer than three visible main stops**. The expected collapsed-route usability threshold is not met. |
| Manual refresh → completed visible answer, same ticket and route | **FAIL** | **FAIL** | No readable completed refresh receipt was found within the 45-second interaction budget, even after scrolling the source card. The old test could tap before the initial source check finished; the next run explicitly waits for that check before timing manual refresh. Do not assign a backend root cause from this observation alone. |
| Select same origin/destination → explicit refusal **and no stale train results** | **FAIL** | PASS | Online profile saw **seven train rows immediately** after the impossible trip, but the old assertion did not wait for the asynchronous ListAdapter diff. This observation **does not yet prove persistent stale results**; the next run waits three seconds before failing. |
| Settings → Back → usable search | PASS | PASS | Search remained usable after navigating back. |
| Primary controls have accessible labels and ≥48 dp visible touch targets | **FAIL** | **FAIL** | Settings measured ~44 dp high online/offline; an earlier run also measured Train mode at 40 dp. These controls miss the accessibility target. |

**Scores:** 2/5 methods passed online; 2/5 passed offline. These are *not* a representative percentage of product quality, and failures in a journey are not cancelled by unrelated passing unit tests. The runner enforced a failing verdict for both profiles. A warm-start metric was **not credited**: that stage is after the acceptance suite and did not execute once the suite failed.

## Other gates and performance

- Build, four unit partitions, static checks, APK publish, screenshots and the separate [current-head API 34 core E2E run](https://github.com/tarungittarun/Checktrain02/actions/runs/39010955342) succeeded. Core E2E is **not** the same as full API 24/density/release certification; its separately compiled APK also does not replace these published-byte checks.
- The [Android workflow](https://github.com/tarungittarun/Checktrain02/actions/runs/39010955293) failed **because both new traveller acceptance jobs failed**. The online acceptance job took 279 seconds and offline 198 seconds: **both exceed 180 seconds**. Android workflow wall time was approximately 440 seconds; the core E2E report marked its five-minute target missed at 583 seconds. Performance remains a failed requirement, not a timeout to waive.
- The phone-app telemetry comparison is still **unverified**. Fixture/source contracts cannot turn a website page clock into a measured train movement.
- PR #3 remains open and unmerged. The rolling APK can be installed for hands-on investigation but must **not** be described as certified or fixed.

**Still not established by this release-byte stage:** published-APK behaviour on API 24 or R8 release, upgrade-with-data-retained from an older signed APK, automated screen-reader narration, real phone hardware/battery benchmarks, and independently authenticated minute-by-minute phone-app telemetry. The existing wider E2E suite covers some API/density/release flows using separate builds, not these exact published bytes. No weighted product-quality percentage should be inferred from either suite.

## Follow-up priorities

1. Re-run the guarded manual-refresh journey, then diagnose any persistent missing receipt from the actual viewport and source state; preserve screenshots and logcat.
2. Re-run the settled invalid-trip result check; clear results if they truly persist after the adapter finishes updating.
3. Make Settings and Train mode meet ≥48 dp touch targets; keep at least three main stops visible at large font without misaligning stop names/clocks.
4. Re-run **these same hard assertions on the next published APK**. Do not lower thresholds to turn red into green. Full API 24/density/release and 180-second performance certification remain outstanding.
