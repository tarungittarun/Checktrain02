# R127 · logo/Home and tracking polish — 18 Oct 2026

This is a change checklist, not a claim of live railway correctness. Keep PR #3 open until the owner's phone check. The downloadable `latest-apk` URL must not be called R127 until the current-head build publishes it.

- [x] **Home logo with populated results:** do not re-select the already-active station mode (which cleared the ViewModel report but left old RecyclerView rows). Keep the source/count summary, heading, sort, and same-mode result; reopen the search card at wrap-content height and reset list scroll to top. A new render fixture has **three populated rows** and checks geometry; a device journey searches a station pair, taps the logo and checks the retained result/summary and bounded gap.
- [x] Tracking top-left now shows the locomotive logo and **TrackTrain**, still returning to the list; Settings, Settings Back and Refresh are 48dp icon targets with content descriptions. Refresh is blue and rotates while a manual/Auto request runs; rotation stops on completion or screen exit.
- [x] The website-supported intermediate marker reads **At [station]** and **As on [HH:mm]**; this is its *last reported event*, not a GPS or inferred current position. Leave NTES actuals and website claims distinct.
- [x] Remove **About time** button. The source picker gets its full row width; long-press it for the existing time explanation and technical **Source details**. Give the long-press action a TalkBack description.
- [x] Render manual and Auto response durations (both in the receipt and source picker) to **one decimal second**, without inventing seconds on a minute-resolution source-update time. Both use the existing selected-source-first refresh chain.
- [x] Only **NTES** and **Where Is My Train · website (unverified)** appear in the Settings and tracking source selectors. Nonselected diagnostic/fallback implementations remain internal; the website and WIMT web cache are **not** phone-app telemetry. Do not rename a probe as verified phone-app data.
- [x] Shorten the distance-based gap between halt rows about 25%, and shorten mapped-intermediate spacing slightly, with a 76dp minimum when a site-reported marker needs both text and age. Bump section labels from 10sp to 11sp and fixed route headings from 11sp to 12sp medium, and turn off animated list item replacement to avoid jumps during live updates.
- [x] Version and diagnostic stamp updated together: versionCode **57**, versionName **1.0.56-r127**, stamp **R127**.
- [x] Static resource/reference/member/lexical, E2E selector and flow-inventory checks passed locally. **Pending:** current-head Android compile/render/unit checks, updated-APK full E2E, measured row counts/wall times, SHA-256 and APK delivery. Static checks do not prove layout on a phone or live parity.

## Verification log (fill from the updated APK, not R126)

| Gate | Result |
| --- | --- |
| Android build + render/unit tests | First attempt `39103040010`: APK compiled/published and six published-APK traveller partitions passed, but render partition failed four strict geometry/readability checks (route identity clipped by the new brand; ruler floor still asserted 80dp). Two-line header and bounded-ruler test corrected; replacement pending. |
| Current-head full E2E device rows | Pending |
| Strict APK correspondence and per-job wall audit | Pending; do not relax if red |
| APK byte count, SHA-256 and version | Pending |
| On-phone live data parity | Not verified by an emulator |
