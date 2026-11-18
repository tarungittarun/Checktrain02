# Strict published-APK test — efaf0fd / R118

**FAIL. The new end-user acceptance gates reject this APK.** Source SHA: `efaf0fd3ff03b570c6e258c01cfe45ad816e65b6`. [Android build, publish and traveller tests](https://github.com/tarungittarun/Checktrain02/actions/runs/39011640170) completed; overall Android workflow **failed** because both traveller profiles failed. [Download latest debug APK](https://github.com/tarungittarun/Checktrain02/releases/download/latest-apk/app-debug.apk) only for hands-on investigation (the URL is rolling; release notes must still say `efaf0fd` for this report). APK versionCode 48 / `1.0.47-r118`, 11,082,968 bytes; SHA-256 `1627c740986b4a3cb99ad7cdc71d4b4047215d234864ad79278f25769f176e8d`.

## Method, not a manufactured success percentage

The workflow verifies the APK signature and its stable **test-only** update key, ZIP/offline timetable/package/version/permissions, and exact byte equality among the same-run build artifact, release download and installed PackageManager `base.apk`. **All these preflight checks passed in both profiles.** It then operates the downloaded APK through UiAutomator, not app internals, in five separate end-user journeys on Android API 34: regular online, and offline at 1.30× font with a reduced viewport. The strict test transcript, screenshots, window hierarchy, logcat and APK/hash evidence are uploaded as `traveller-evidence-online` and `traveller-evidence-offline` on the [Android run](https://github.com/tarungittarun/Checktrain02/actions/runs/39011640170); failing assertions are also posted on the commit.

| End-user journey | Online | Offline / large text | Finding |
| --- | --- | --- | --- |
| Ticket number → exact train → route/date/schedule/qualified position | PASS | **FAIL** | Fewer than **three visible main stops** in the offline large-text viewport. The route is not legible within the requested collapsed viewport. |
| Manual refresh after initial source check settles → completed receipt and preserved route | PASS | PASS | The earlier blank-receipt finding was a **test precondition error** (tapping during initial source check); the guarded test now passes. No live train movement parity is established by this pass. |
| Same-station query → explicit refusal **and no train rows** | **FAIL** | PASS | Train rows remained visible **three seconds after** the explicit refusal in the online profile. This is not a transient one-frame ListAdapter diff. |
| Settings → Back → usable search | PASS | PASS | Round-trip and new query succeeded. |
| Accessibility-labelled primary controls with ≥48 dp touch targets | **FAIL** | **FAIL** | Settings target measured ~44 dp in both profiles; below the 48 dp threshold. The Segment style also specifies 40 dp, so other search controls need examination. |

**3/5 journeys passed online; 3/5 passed offline.** Do **not** present this as a product-quality percentage. The suite found concrete user-facing failures that earlier green unit/core-E2E suites did not reject. Warm-start performance was **not credited**: the startup timing step is after the acceptance suite and was not reached on a failed run.

## Root-cause leads (not guesses about live telemetry)

- `app/src/main/res/values/themes.xml` defines `BtnGhost` with `android:minHeight=44dp` and `Segment` with `android:minHeight=40dp`: this matches the measured accessibility failure. Increasing the minimum to ≥48 dp needs layout remeasurement, not just a green static assertion.
- `TrainFinder` returns an empty `SAME_STATION` report, but `MainViewModel.search()` still calls `fetchLivePair(fromText, toText, report)` for all two-ended station searches; `applyLive()` may post `assetReport.copy(hits = hits)` after the refusal. This is a plausible path by which a later live answer repopulates invalid-trip rows. The persisted rows are a **confirmed visible failure**; the precise callback sequence still needs log/diary evidence before claiming the backend alone caused it.
- `BoundedScrollView` caps the source card at 53% of screen height, while the station list competes for the remaining viewport. The reduced-height, large-font device showed fewer than three visible main stops. Measure the actual screenshot and station-list bounds before changing this constraint; do not remove scheduled clocks or hide information to make the test pass.

## Other gates and limitations

- Android compile, field-test compile, four independent JUnit partitions, static checks, APK publish and screenshot jobs **passed**. The exact released APK's v2 signing and update-key fingerprint passed, as did its package/permissions and published-versus-installed hash comparison. These are **not** evidence that the traveller journeys passed.
- [Current-head core E2E](https://github.com/tarungittarun/Checktrain02/actions/runs/39011640169) ran in parallel. It tests a *separately built* APK and does not supersede the failing published-byte acceptance. The full API 24/density/R8 release matrix was **not** run at this SHA; those remain unverified for this build.
- Online traveller job took **218 s** (>180 s); offline took **164 s**. Android workflow took about **496 s** wall. The three-minute job/workflow requirement is still unmet. There was no timeout extension masquerading as compliance.
- Published-byte tests currently do **not** verify older-version upgrade-with-data-retained, API 24, real-device battery, automated screen-reader narration or authenticated Where Is My Train phone-app telemetry. Website timestamps are not train-position evidence.
- [PR #3](https://github.com/tarungittarun/Checktrain02/pull/3) remains open and unmerged. **The APK is available for investigation, not certified.** The next app changes should fix the above failures and rerun the same hard gates, not weaken them.
