# R122 tracking changes and evidence (2026-10-17)

## What changed

- TIMETABLE and the corresponding scheduled arrival/departure clocks now share the **left** edge of the timetable column. The fixed STATIONS and ACTUAL headings stay aligned with their cells.
- The last reported train glyph shows short **event** age below its logo (including `<1m` alongside the under-one-minute check); the event clock and source identity remain in the accessible description. It is not a GPS fix.
- A first usable source reply and a manual/opt-in Auto refresh can send a finite concentric wave from the **visible reported train glyph**. The overlay is clipped to the route viewport. No marker moves in response to the wave or to elapsed time, and there is no wave when only a timetable is available.
- The old always-on boxed paint-clock is replaced by a one-second unboxed growing/fading response score, such as `1 sec 14:52` or `1 sec 14:52:23`. It pairs the measured request/refresh duration with the selected source's **own data/publication update clock** when one is available; otherwise `—`. A page clock is not a train event. Second digits appear only for second-granular website/cache stamps, not for minute-granular NTES data. Source and caveat remain in accessibility/Info; refreshing a failed source cannot advance the old stop event.

## Why between-intermediate tracking is still unresolved

The attached 16225 diary (`opentrack-diary-20261017-151300.txt`) records NTES's TKE event at **14:54**, unchanged at 15:02. The selected `whereismytrain.org.in` website repeated TKE **14:52**, later exposed a **past** MSS 15:04 report in its 15:09 reply and subsequently a BDVT 15:09 report. These are discrete reports, not a distance measured between MSS and BDVT or between the map's named intermediate dots. The separately probed WIMT **web cache** did not produce a fresh, route-bracketed candidate. Neither selected source in this capture supports moving the blue train to an in-between location. The Where Is My Train **phone app** has a different data path; we do not possess its onboard cell-derived position or know the indicator's semantics. Do not present a schedule-based estimate or a website page-refresh clock as measured movement.

## Verification status

- Local static resource/reference/lexical checks passed (resources 0, references 0, receiver-member 0, Kotlin lexical 0, 61 Python CI self-tests pass). The initial R122 build caught one Kotlin-incompatible test assertion; the next build caught two outdated left-age render expectations. These were fixed without suppressing checks; only the current-head runs below count as passes.
- Local Gradle tests unavailable (no JDK or Android SDK in this sandbox); CI is the compiler and published-byte tester. The **current-commit** Android and E2E results are recorded separately below. A green device partition does not override a failed strict artifact/parity/timing audit.
- Owner-phone acceptance and between-intermediate motion parity: **unverified**. PR #3 must stay open until the owner confirms the APK works.

## Measured current-head result · c79e2b2 (not owner-phone verification)

- [Android run 39023614288](https://github.com/tarungittarun/Checktrain02/actions/runs/39023614288): **SUCCESS**. Four unit partitions: **375/375** JUnit cases, 0 skipped; **33** pixel/layout render cases. The debug APK and instrumented field-test APK compiled; release publication and **6/6** separate exact-published-byte online/offline journey/trust/refresh partitions succeeded. The acceptance partitions verify the rolling release's installable bytes, not the still-separate E2E build.
- [E2E run 39023614332](https://github.com/tarungittarun/Checktrain02/actions/runs/39023614332): 31/31 API 24/API 34/density/release device rows passed; strict audit **FAILED**. Its independently compiled E2E DEX does not match/is unverified against the Android-published APK. Phone-app telemetry parity remains unverified. The 180 s ceiling is missed in **29/55** measured jobs/workflows (Android 670 s; E2E 845 s). Do not describe the strict audit as green.
- [Published R122 APK](https://github.com/tarungittarun/Checktrain02/releases/download/latest-apk/app-debug.apk): versionCode 52 / `1.0.51-r122`; **11,095,336 bytes**, release metadata SHA-256 **`1c5ec333cb37a4bd3363cf7f029a7c6100648b6ae397b908925203f77ab5ef9a`**. Signed/updateable debug build under the repository's existing publishing convention. It was not exercised on the owner's phone here.
- The UI's wave and one-second score communicate a *refresh*, not movement. With the captured 16225 inputs there is still **no honest measured position between intermediate stations**. PR #3 is open and must not be merged until the owner confirms the APK.
