# Installed-app WIMT diagnostic: 20-second observations for 30 minutes

## Scope

Four independent GitHub Actions workflows each build an APK pair from this commit in CI, install it into a separate Android emulator, and test a distinct train:

| Workflow | Train |
| --- | ---: |
| `wimt-app-soak-20s-30m-16227.yml` | 16227 |
| `wimt-app-soak-20s-30m-20652.yml` | 20652 |
| `wimt-app-soak-20s-30m-12002.yml` | 12002 |
| `wimt-app-soak-20s-30m-20651.yml` | 20651 |

These are fixed, distinct members of the app's existing schedule-candidate set. Schedule membership is not evidence that a train is currently moving or that WIMT has a qualified live response. Each emulator's own installed-app result decides that. All four workflows call the shared reusable workflow; they do not overwrite or change the unrelated direct-source, website, cache, device-acceptance, or campaign workflows.

The test drives the installed app through its visible UI and accessibility tree. It attempts to select **WIMT .in live-status feed**, captures the in-app source details and table evidence where accessible, and records one UI observation at elapsed time 0 and every 20 seconds through 30 minutes (91 target points, including both endpoints). The existing app's opt-in **Auto · 1 min** cadence is unchanged. Twenty seconds is the observation interval, **not** a new 20-second network polling interval.

## Safe behavior on source failure

A fresh, run/date/route-matched WIMT response with source-reported actual stop rows, visible WIMT-attributed cells, a current-station claim, and the app-side running-status qualification is required before Auto can be enabled. If that initial gate fails, the test records the failure, verifies Auto is OFF, and continues the 30-minute observation passively. If an Auto refresh later fails, loses qualification, or misses its expected receipt, it disables Auto and continues passive UI sampling. It does not keep retrying a refused or unqualified source just to complete the timer. Such a run is a **completed observation but a failed WIMT acceptance**, not a pass.

## Captured metrics

Each 20-second trace row includes target and actual elapsed time, sampling jitter, whether WIMT remains selected, visible WIMT-attributed cell count, source-row visibility, a bounded source-summary class, Auto state, refresh receipt time and result class, tracking-screen/foreground visibility, and (at start and five-minute marks) Android `dumpsys meminfo` total PSS and `dumpsys cpuinfo` process percentage where available. The in-app source-details captures add HTTP status, response size, parser outcome, run/date and route matching, freshness classification, source row/cell counts, current-station claim, running-status qualification, and disclosure status. The final report summarizes receipt counts and intervals, missed slots, observation timing, memory, screenshots, source failures and the exact workflow train.

Artifacts contain the in-app trace, device log, APK-install checksum proof, instrumentation output, original screenshots/accessibility hierarchies, and a redacted `report.md`/`result.json`. Raw response bodies, request URLs/headers, and unrestricted source text are not copied into the report.

## Existing evidence and unresolved issue

The earlier installed-app attempt `44129158301` for train 20652 failed before timed observation: HTTP 200 and run/route matching were reported, but freshness was not established, with zero parsed actual rows, zero visible WIMT cells, and 0/46 receipts. A different installed-app attempt `44120434787` for train 12627 qualified through approximately minute 30, then lost the selected source at about minute 35.3 and did not reach 45 minutes. Those are app-specific failures, not evidence of a current successful app run.

The 45-minute run `44134045529` observed `whereismytrain.in/cache/live_status` directly, without installing or opening the app. It cannot diagnose or demonstrate the installed app's behavior. WIMT .in, WIMT .org.in, NTES, eRail, the WIMT web cache, and the Google WIMT Android app remain distinct sources.

**Root cause remains undiagnosed until the new installed-app artifacts are reviewed.** The reports distinguish request failure/refusal, run/date or route mismatch, stale/unknown source stamp, missing source actual rows/current-station claim, parser failure, UI attribution/selection loss, missed refresh receipts, and app-screen or resource issues. A fresh response stamp is not event time; source claims and timetable rows do not independently prove train movement or GPS.

## Running

Each fixed-train workflow can be manually dispatched. On this PR branch, a push containing `[wimt-app-soak-20s]` in the commit message runs all four. Each job builds and installs its own same-run APK pair; no local APK build or owner-phone installation is involved. Inspect each workflow's evidence artifact and compare by its fixed train number. A green build or completed 30-minute timer alone is not a source-qualified WIMT pass.
