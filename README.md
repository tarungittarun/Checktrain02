# OpenTrack India

Offline-first train search and live running status for Indian Railways. No account, no API key, no
ads, no analytics, no proxy server of ours in the middle.

The app answers two questions, and both of them work with no signal at all:

1. **Which trains run between A and B around the time I want to travel?** Answered from a timetable
   database compiled into the APK (~5.5 MB compressed). A search touches no network.
2. **Where is that train right now, relative to where it should be?** A time–distance chart of the
   schedule against the last confirmed live event, plus the stop-by-stop delay table.

```
┌─────────────────────────────┐   ┌──────────────────────────────────────────┐
│ Search                      │   │ 12951 · Mumbai Central–Sabarmati Express │
│  [ From: Chennai Central  ] │   │ at ITARSI BG · +14m late                 │
│  [ To:   New Delhi       ]  │   │ LIVE · updated 42s ago                   │
│  Today · 10:05  −1d now +1d │   │  ┌────────────────────────────────────┐  │
│  [ Find trains ]            │   │  │  time–distance chart (canvas)      │  │
│                             │   │  │  grey = schedule, green = live      │  │
│ 12951 17:00 → 08:32 (+1d)   │   │  └────────────────────────────────────┘  │
│ 12615 20:45 → 06:10 (+1d)   │   │ next stop at 14:32 · 3h 05m to SBAT      │
└─────────────────────────────┘   └──────────────────────────────────────────┘
```

## Get the APK

There is nothing to install on your machine. The APK is produced by GitHub Actions:

1. **Actions** tab of this repository → *android* → **Run workflow** (or just push a commit).
2. Open the finished `build` run → **Artifacts** → `opentrack-debug-apk`.
3. Copy that file to the phone, tap it, allow "install unknown apps".

For a release build (smaller, R8-shrunk, installable but only updatable in place if you set the
signing secrets) push a tag: `git tag v1.0.0 && git push --tags`. The `release` job attaches
`app-release.apk` to the tag.

If `Actions` is disabled on a fork, enable it in *Settings → Actions → General → Workflow
permissions* — the workflow needs only `contents: write` on the tag job to attach the artifact, and
nothing at all on the default branch build.

## What is in the box

| | |
| --- | --- |
| Timetable | 6 775 trains, 141 908 stops, 8 990 stations with coordinates, 135 133 inter-station legs |
| APK size | the timetable asset is 5.52 MB compressed; the build's own bytes are small. The exact number is measured on every CI run and printed in the job summary — not guessed here. |
| Dependencies | 6 AndroidX artifacts, zero third-party libraries at runtime |
| Network | one HTTPS GET for live status; nothing else, ever |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, and `ACCESS_COARSE_LOCATION` (off by default) |
| Min / target SDK | Android 7.0 (API 24) / 14 (API 34) |

## The ±24 hour search

A search takes a *date and time*, not a date, and returns every run whose boarding falls within
`target − 24 h … target + 24 h`. Two cases make that window necessary rather than fashionable:

* the train that boarded at 23:50 **yesterday** and arrives tomorrow morning is the answer to a
  00:10-today search;
* a run that leaves at 00:05 for a 23:00 search is a train you can still catch tonight.

Each candidate day is then checked against the train's running-day bitmap, so a Monday-only train is
not offered for a Wednesday — and, if nothing survives, the result says so ("12951 runs only on Mon")
instead of showing an empty list.

## Live status: where the data comes from

`erail.in`'s public enquiry endpoint, fetched directly from the phone. It is the only keyless machine
interface in this space that has been stable for years — a flat `~`-delimited record stream, not HTML
and not session-bound JSON.

Three properties of that source drive the design:

* **It reports events, not GPS.** "At Vadodara, departed 12:22, 14 min late" is the fact we get. The
  moving marker between stations is *derived* — interpolated along the scheduled leg, capped short of
  arrival, never allowed to move backwards. See `docs/ARCHITECTURE.md` for the rules.
* **Empty fields are dropped by the server**, so column positions are not stable. The parser classifies
  each field by its *shape* instead of its index.
* **It has no SLA.** Every screen states its own provenance: `LIVE · updated 42s ago`,
  `LIVE · cached`, `OFFLINE · schedule only`, `NO DATA`. Nothing is presented as live that is not.

If the endpoint ever starts requiring a key or changes its column order, **Settings** on the device
lets you paste a new URL template and a column map. That recovery path is part of the product, not a
debug feature.

GPS is optional and secondary. If the phone is on the train and a fix places you more than one segment
ahead of what the feed reported, the measured position wins and is labelled `DERIVED · GPS`.

## Build it yourself

Requirements: JDK 17, Python 3.10+, and network access for the first Gradle run. Everything else is in
the repository.

```bash
python3 tools/build_refdb.py --profile halts \
    --out app/src/main/assets/ref/refdb.dbgz   # deterministic, verified, ~4 min
./gradlew :app:assembleDebug                    # or :app:assembleRelease
./gradlew :app:testDebugUnitTest
```

`syncRefDb` is wired to `preBuild`, so a clean `./gradlew assembleDebug` also works: it builds the
asset if `tools/build_refdb.py` can, and fails loudly rather than shipping an APK that cannot search
offline.

To rebuild the *full* timetable (every halt, ~3× the size) use `--profile full`. The `halts` profile
keeps the stations a traveller can actually board at, which is what both the search and the delay
table need.

### Updating the timetable

The bundled data is a snapshot; `meta.built_at` is shown in Settings and in the data chip on the
search screen. Regenerating it is one command and one commit — the schema is versioned
(`REFDB_SCHEMA_VERSION`) and the app refuses to open a database built for another version rather than
misreading it.

## Tests

**Asking a person, not a runner:** `docs/TEST_CARD.md` is the full card (install link, what each screen must
do, the three-line reply we want back), and `docs/TEST_CARD_SHORT.txt` is the same thing plain enough to paste
into a messaging app. Both end with the log-capture steps, because "the pill says OFFLINE" is only useful once
the device says why — and a card that starts with the file size is a card that catches a stale build before it
wastes an afternoon.


Two suites, and the split is the point.

`./gradlew :app:testDebugUnitTest` runs the JVM suite with no emulator and no network. It covers the
parts that are silent failures on a train:

* the hand-written calendar maths, against `java.time`, over ~80 years (a one-day error moves every
  search);
* the ±24 h window rules, including both midnight spillovers and the tie-break;
* the wire parser: dropped empty fields, error sentinels, a reordered column map, a truncated tail,
  and the feed's own generation stamp (`2026-10-11-7-56-55`), which is the only freshness fact this
  source publishes;
* the position rules: halt hysteresis, the 0.97 interpolation cap, monotonicity, staleness demotion,
  GPS override and its accuracy floor, "report not on the route" handling;
* snapshot codec round-tripping (the cache format and the user-supplied endpoint share one codec, so a
  key-name mismatch is invisible in one direction only);
* the screens themselves, painted: Robolectric inflates the real layouts, lays them out at 360 dp and
  411 dp and rasterises with Skia, and the assertions are about pixels and rendered text — that the
  chart drew a polyline, that no label is cut with an ellipsis, that a schedule-only row prints a dash
  in the live column and not the timetable a second time.

`./gradlew :app:connectedDebugAndroidTest` runs the **device** suite — UiAutomator driving the installed
APK from outside its process. JVM tests cannot see the things only a device has: a first run that unpacks
a 14 MB timetable while you type, a keyboard, a network that answers (or doesn't), R8 and resource
shrinking in the release artifact, a density that does not fit. Every flow it covers is listed in
**[docs/E2E_FLOWS.md](docs/E2E_FLOWS.md)**, together with the flows that are declared uncoverable and
why; `.github/workflows/e2e.yml` fails the build if the list and the tests ever disagree, so "we have
N tests" can never again stand in for "the tracking screen is tested".

```
# the whole flow suite on a connected phone or a local emulator
./gradlew :app:connectedDebugAndroidTest
# one running train, measured against the live feed, with the per-stop facts in logcat
adb logcat -s opentrack-live &
adb shell am instrument -w -e class in.opentrack.india.realdevice.LiveFeedFieldTest \
  -e train 12627 -e from SBC -e to NDLS \
  in.opentrack.india.test/androidx.test.runner.AndroidJUnitRunner
```

On a branch push, CI runs one **required API 34** emulator row (including offline flows) and a
600-event random smoke plus a 600-event reopen pass. It records crashes/ANRs and posts a device roll-up.
API 24, large-density/font-scale and the signed R8 release APK are reserved for main or an explicit
`[e2e-full]` commit/dispatch **before shipping**; a normal branch run does not prove them. Likewise,
source reconnaissance runs on main, explicit `[probe]`, or dispatch, not every UI-only change. The runner
checks for KVM and reports a boot failure rather than treating no device as a pass. JaCoCo line coverage
is measured only when the instrumented device produces a readable coverage file; missing coverage is
reported as unavailable, never inferred from a count of declared flows.

## Known limits, stated plainly


* Live status is only as good as the public endpoint; when it is slow, the app shows the last cached
  snapshot with its age rather than a spinner.
* The timetable is a snapshot of a public dump. Cancelled rakes, specials and diverted routings are
  not in it; a live report naming a station the offline route does not contain is shown as unresolved
  instead of being snapped to the nearest plausible stop.
* No screenshots/brand assets: this is a functional build, not a store listing.
* The device suite proves the app's *behaviour*, not the upstream feed's honesty: with no live columns
  in the response there is nothing to check an actual minute against, so those rows assert the app said
  "this is the timetable" instead. A second runner needs real live minutes, which needs an endpoint (see
  the note above) — that is the one gap the suite cannot close by itself.
* CI's emulator rows need KVM. The `min24` row is configured to try without it (`-no-accel`) and is the
  row that must boot on a standard runner; the modern rows may report `boot=failed`, which the roll-up
  shows as skipped-with-reason rather than green.
* Only English; `resConfigs "en"` is deliberate (it removes ~700 kB of AndroidX translations).

## Privacy

Nothing is collected. No analytics, no crash reporter, no ad SDK. The only outbound request is the
live-status fetch, from the device, to the enquiry endpoint named in Settings. Location is read once
per screen, on demand, only if you enable it in Settings, and never leaves the device.

## License

The code in this repository is yours to use under the terms you publish it with. The *data* is derived
from a public timetable dump and belongs to the original source; treat it as reference material, not a
guaranteed schedule — check before you travel.

### Updating test APKs

The rolling **debug** APK is signed with the explicitly public, test-only certificate at
`tools/ci/test-only-debug.p12` (password `android`). It is **not** a production credential;
release signing still requires a separate private keystore. Builds through R52 used a
fresh CI key for every build, so Android cannot update them: **uninstall the old APK once**
(back up any diagnostics/settings you need first), install R53 or newer, then later rolling
debug APKs can update in place without uninstalling. Do not install an APK from an untrusted
source simply because it is signed with this publicly known debug key.
