# R127 · logo/Home and tracking polish — 18 Oct 2026

This is a change checklist, not a claim of live railway correctness. Keep PR #3 open until the owner's phone check. The latest release artifact must be identified by its source commit and digest before download.

- [x] **Home logo with populated results:** do not re-select the already-active station mode (which cleared the ViewModel report but left old RecyclerView rows). Keep the source/count summary, heading, sort, and same-mode result; reopen the search card at wrap-content height and reset list scroll to top. A new render fixture has **three populated rows** and checks geometry; a device journey searches a station pair, taps the logo and checks the retained result/summary and bounded gap.
- [x] Tracking top-left now shows the locomotive logo and **TrackTrain**, still returning to the list; Settings, Settings Back and Refresh are 48dp icon targets with content descriptions. Refresh is blue and rotates while a manual/Auto request runs; rotation stops on completion or screen exit.
- [x] The website-supported intermediate marker reads **At [station]** and **As on [HH:mm]**; this is its *last reported event*, not a GPS or inferred current position. Leave NTES actuals and website claims distinct.
- [x] Remove **About time** button. The source picker gets its full row width; long-press it for the existing time explanation and technical **Source details**. Give the long-press action a TalkBack description.
- [x] Render manual and Auto response durations (both in the receipt and source picker) to **one decimal second**, without inventing seconds on a minute-resolution source-update time. Both use the existing selected-source-first refresh chain.
- [x] Only **NTES** and **Where Is My Train · website (unverified)** appear in the Settings and tracking source selectors. Nonselected diagnostic/fallback implementations remain internal; the website and WIMT web cache are **not** phone-app telemetry. Do not rename a probe as verified phone-app data.
- [x] Shorten the distance-based gap between halt rows about 25%, and shorten mapped-intermediate spacing slightly, with a 76dp minimum when a site-reported marker needs both text and age. Put the provenance badge above long source warnings at large font sizes. Bump section labels from 10sp to 11sp and fixed route headings from 11sp to 12sp medium, and turn off animated list item replacement to avoid jumps during live updates.
- [x] Version and diagnostic stamp updated together: versionCode **57**, versionName **1.0.56-r127**, stamp **R127**.
- [x] Static resource/reference/member/lexical and E2E flow-inventory checks pass locally (57/57 flows mapped). A dense-font failed-website fixture requires its source-named headline and badge in the first-glance card; the current-head Android render and device jobs passed. Static checks and device flows do not prove live phone-app parity.

## Verification log (`34a3277` · latest APK)

| Gate | Measured result and remaining risk |
| --- | --- |
| Android build + rendering | [39109746442](https://github.com/tarungittarun/Checktrain02/actions/runs/39109746442) **PASS**: compiled/published the updateable APK; **393/393** JVM tests passed including **39/39** rendered-screen cases (ACTUAL painted pixels and source-specific dense headline). **6/6** published-APK acceptance partitions passed (online/offline journeys, trust, refresh). |
| Full E2E installed-device rows | [39109746317](https://github.com/tarungittarun/Checktrain02/actions/runs/39109746317): all **31/31** installed rows passed, including API 24, API 34, large-font density, release smoke and the six exact-published-APK traveller partitions. Workflow conclusion **FAIL**, not green, because strict audit rejects independent-build APK DEX mismatch and time budget; device rows passing do not override this. E2E's separately built binary cannot be certified byte-identical to the published APK. |
| Strict APK and time audit | **FAIL**: independently built E2E APK DEX hashes unverified/different; **33/55** measured workflows/jobs exceeded 180s or were unmeasured. Full E2E wall **963s (16m03s)**, over the requested under-15m target (but under 19m); Android wall **401s (6m41s)**. No thresholds or source assertions were relaxed. |
| APK byte count, SHA-256 and version | Published from `34a3277`: **11,125,452 bytes**, SHA-256 `35c784443bc261a7ae9f8400b691b962d9a8e9c114d8b8ccc309eb59ef169fc8`, package `in.opentrack.india`, versionCode **57**, versionName **1.0.56-r127**. Rolling URL: https://github.com/tarungittarun/Checktrain02/releases/download/latest-apk/app-debug.apk |
| On-phone live data parity | **Not verified** by an emulator or any authenticated phone-app feed. Website timestamps are not movement telemetry. |

This is **not** a green strict-audit/release verdict: the E2E workflow exits red for the binary-correspondence and performance gates. Do not merge PR #3 before the owner's phone check. Updateable signing remains unchanged.
