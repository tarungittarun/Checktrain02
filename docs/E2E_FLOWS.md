# End-to-end flows: the list the emulator workflow is measured against

This file is the definition of "tested" for this app, and it is machine-checked: `tools/ci/flow_coverage.py`
fails the build if a flow listed here has no test that claims it, or if a test claims a flow that is not
listed here. The alternative — "we have 118 tests and they pass" — is a count, not a coverage claim, and
counts are how the suggestion bug shipped.

Every flow is driven from **outside the app**, through the real window, on a real emulator, against the
**built APK** (`UiAutomator`, i.e. taps on pixels and reads the accessibility tree a person's finger and a
screen reader would see). Nothing here pokes at internals: if a flow needs a reflection hook to test, the
flow is not user-visible and belongs in the JVM tests instead.

Reading the table: *What the traveller sees* is the assertion. A flow whose expectation depends on what the
railway happens to be reporting today says so, and its test asserts the invariant instead of the number
(a test that goes red because a train was on time is worse than no test).

## Covered flows

| Flow | Screen | What the traveller sees | Test | Runners |
| --- | --- | --- | --- | --- |
| `cold-start` | search | First launch after `pm clear` reports the offline timetable being installed with a real percentage, then a usable search screen — never a blank frame, never an ANR | `ColdStartFlowTest#coldStartInstallsThenAnswers` | cold, all |
| `data-chip` | search | The data chip states what is installed (counts or "not installed"), which is what makes "no results" interpretable | `ColdStartFlowTest#dataChipStatesWhatIsInstalled` | all |
| `type-ahead-station` | search | Three letters in Board-at or Get-off-at open a list of matches; tapping one puts the *name* in the field and the code in the "Selected" chip | `StationSearchFlowTest#threeLettersOfferMatchesThatFillTheField` | all |
| `type-ahead-train` | search | Three digits in the train box offer trains; tapping one fills the number | `TrainSearchFlowTest#trainBoxSuggestsAndFills` | all |
| `type-ahead-min-length` | search | Two characters open nothing, on purpose: "B" matches 400 stations | `StationSearchFlowTest#twoLettersAreSilentByDesign` | all |
| `type-ahead-never-silent` | search | Every query ≥3 chars answers with rows or with a sentence saying why there are none — "still installing", "nothing matches", or a failure. Never an empty box | `StationSearchFlowTest#everyQueryAnswersWithRowsOrWithAReason` | all |
| `both-ends-search` | search | Both ends filled (by tapping suggestions) runs the search and shows results or a named reason | `StationSearchFlowTest#bothEndsRunASearch` | all |
| `empty-query-feedback` | search | Find with a blank field states exactly what is missing | `StationSearchFlowTest#findWithBlankFieldsSaysWhatsMissing` | all |
| `unknown-station` | search | A string that matches no station is refused with the code advice, not silence | `StationSearchFlowTest#unknownStationNamesTheFix` | all |
| `same-station` | search | Boarding and alighting at the same station is refused explicitly | `StationSearchFlowTest#sameStationBothEndsIsRefused` | all |
| `day-shift` | search | −1d / now / +1d move the travelling date, and the shift survives a re-search (the ±24 h window is the feature) | `StationSearchFlowTest#dayButtonsMoveTheDate` | all |
| `swap` | search | Swap exchanges the two ends, including their resolved codes, and the result set follows | `StationSearchFlowTest#swapMovesTheResolvedCodes` | all |
| `train-mode-search` | search | "Train no. / name" mode searches the offline master by number or name | `TrainSearchFlowTest#trainModeFindsByNumber` | all |
| `wrong-date` | search | A date with nothing running in the window says so and suggests ±1 day | `TrainSearchFlowTest#noRunOnThatDateSaysSo` | all |
| `bogus-train-number` | search | Five digits that exist nowhere produce the no-train sentence, not an empty list | `TrainSearchFlowTest#nonexistentTrainNumberIsExplicit` | all |
| `settings-entry` | search | The Data source button opens the settings screen | `SettingsFlowTest#everyControlIsReachableAndBackReturns` | all |
| `track-open` | tracking | A result's Track button opens that train, that date | `TrackingFlowTest#openingAResultShowsThatTrain` | all |
| `live-pair-list` | search | The pair list names its source — rows carry `via erail`, or the screen states the feed died; unnamed rows are the founding bug's door and fail the row | `StationSearchFlowTest#pairRowsNameTheirSource` | all |
| `details-live-identity` | tracking | The details screen asks erail what this number runs TODAY and relabels the route line — confirmed, or both readings shown on mismatch, or the untouched timetable label when nothing answered | `TrackingFlowTest#routeLabelGainsTheLiveIdentityConfirmation` | all |
| `headline-position` | tracking | The headline names where the train is or says plainly that only the timetable is known | `TrackingFlowTest#headlineAlwaysNamesAState` | all |
| `stop-table-columns` | tracking | Every halt row carries SCH times; the LIVE cell is either a reported minute or a dash — never a reprinted schedule minute | `TrackingFlowTest#liveCellIsAMinuteOrADashNeverTheTimetable` | all |
| `distance-ladder` | tracking | Distance is 0 at the first halt, the route total at the last, and never decreases between | `TrackingFlowTest#distancesRunFromZeroToTheTotal` | all |
| `boarding-alighting` | tracking | The two searched stations are tagged board / get off | `TrackingFlowTest#boardAndAlightAreMarked` | all |
| `segment-toggle` | tracking | Show full route ⇄ Show my segment changes the row set, and the line under the table says how many rows are hidden | `TrackingFlowTest#fullRouteAndSegmentTogglesTheWindow` | all |
| `progress-bar` | tracking | The progress label and bar are present, in range, and coherent with the headline | `TrackingFlowTest#progressIsInRangeAndWorded` | all |
| `chart-painted` | tracking | The position-against-timetable plot occupies real space with its legend, on every screen size | `TrackingFlowTest#chartHasGeometryAndLegend` | all |
| `age-and-provenance` | tracking | The provenance and age chips are never blank; "live" cannot appear on a screen nothing fetched for | `TrackingFlowTest#ageChipNeverOverClaims` | all |
| `source-disclosure` | tracking | Opening Info explains Act versus Est, position provenance, and that WIMT web cache is not the phone app | `TrackingFlowTest#sourceBlockExplainsTheAnswer` | all |
| `refresh` | tracking | Refresh re-runs the chain and the screen updates without losing the route | `TrackingFlowTest#refreshKeepsTheScreenCoherent` | all |
| `manual-refresh-only` | tracking | Refresh is available but neither Auto nor the separate intermediate-route button remains | `TrackingFlowTest#tableHasOnlyManualRefreshAndExpandableRoutePoints` | all |
| `long-list-scroll` | tracking | A several-hundred-halt route scrolls to the end with rows still readable (recycling correctness) | `TrackingFlowTest#longRouteScrollsWithoutBlankRows` | all |
| `back-nav` | tracking → search | Back returns to the search screen with the previous query intact | `TrackingFlowTest#backKeepsTheSearchQuery` | all |
| `density-change` | tracking | The screen is coherent at every density the fleet runs (no overflow, no clipped plot) — the axis Robolectric cannot fake, since it ignores `fontScale` | `TrackingFlowTest#chartHasGeometryAndLegend` | large, density |
| `settings-open` | settings | The screen shows the install state, endpoint field and column map | `SettingsFlowTest#everyControlIsReachableAndBackReturns` | all |
| `diag-log-collector` | settings | The diagnostic log is inert until the user turns it on, says so on screen once they do, and is emptied by Clear without losing the switch | `SettingsFlowTest#diagnosticCollectorIsInertThenLiveThenEmpty` | all |
| `endpoint-save-reload` | settings | A typed endpoint survives Save and reopening | `SettingsFlowTest#endpointPersistsAcrossReopen` | all |
| `endpoint-rejects-non-http` | settings | A non-URL endpoint is rejected in Settings with visible correction instead of being saved silently | `SettingsFlowTest#junkEndpointSaysSo` | all |
| `column-hint` | settings | A column map is accepted and persisted (the escape hatch for a feed format change must be reachable) | `SettingsFlowTest#columnHintPersists` | all |
| `clear-cache` | settings | Clear live cache is followed by an offline screen that admits it has no snapshot | `SettingsFlowTest#clearingCacheIsVisibleOnTheTrackScreen` | all |
| `manual-refresh-only-settings` | settings | Auto chooser is gone, source override remains | `SettingsFlowTest#settingsHasNoAutoRefreshChoice` | all |
| `airplane-search` | search | With the radio off, search still answers from the device — the whole point of offline-first | `DegradationFlowTest#searchWorksWithNoNetwork` | api34, min24 |
| `airplane-track` | tracking | With no network and no snapshot the screen says there is no live report and never claims "live" | `DegradationFlowTest#trackScreenAdmitsNoLiveReport` | api34, min24 |
| `location-denied` | tracking | With no location permission the app works and never presents a GPS-derived position | `DegradationFlowTest#noLocationPermissionIsNotAnError` | all |
| `location-granted` | tracking | With permission granted, the provenance label is one of the known set and never a bare "live" | `DegradationFlowTest#grantedLocationNeverFabricatesPosition` | api34 |
| `feed-per-stop-data` | live source | The per-stop claim is measured, not asserted: how many halt rows the public feed actually reported today | `LiveFeedFieldTest#whatThePublicFeedActuallyPublishes` | api34, net |
| `screen-explains-itself` | live source | A live-labelled screen with zero reported rows must carry an explanation, on real data | `LiveFeedFieldTest#theScreenExplainsItselfWhateverTheFeedDidOrDidNotSend` | api34, net |
| `override-endpoint` | live source | A configured endpoint wins, and an answer about another train is refused | `LiveFeedFieldTest#aConfiguredEndpointIsPreferredAndChecked` | api34, net |
| `release-smoke` | whole app | The **minified, resource-shrunk release APK** cold-starts, searches and opens a tracking screen with no crash — R8 and shrinking break exactly this and nothing else tests it | `ReleaseSmokeFlowTest#theShippingApkDoesTheBasicFlow` | release |
| `monkey-stability` | whole app | 30 000 random events across the app with no FATAL EXCEPTION, no ANR, no dropbox entry | `tools/ci/monkey_stability.sh` (job, not a test class) | monkey |

## Deliberately not covered, with the reason

A coverage claim that quietly omits the hard parts is worth less than a smaller claim. These are the parts
this workflow cannot reach, and what it would take:

| Not covered | Why | What it would take |
| --- | --- | --- |
| Real motion on a real train | No hardware in a CI runner, and a simulated GPS track is a fixture, not a measurement | a phone in a pocket, once, with `./gradlew :app:connectedDebugAndroidTest` |
| `SensorPositioner` ground-truth correctness | The emulator can feed a mock location, but a mock *speed + accuracy + bearing over a route* is a fixture again; the rules it obeys are pinned in JVM tests (`PositionEngineTest` GPS rules) | an on-device harness that replays a GPX file |
| OEM keyboards and IME quirks | Robolectric and the emulator both use AOSP; the type-ahead path is tested against the framework IME only | a device matrix (Firebase Test Lab / AWS Device Farm), which needs an account and a key — against this project's no-keys rule |
| Notification, widget, share sheet | The app has none, by design | n/a |
| Play-store signing, upgrade over a previous version | Debug-signed APK only; an upgrade test needs two signed versions and a keystore in CI | a second signing config in CI |
| "Recent searches" reopen | `Prefs.pushRecent`/`prefsRecent` exist, and the *persistence* is covered by JVM tests, but no UI exposes the list yet, so there is no flow to drive | add the row to the search screen, then a test |
| Telephony state changes mid-refresh | `svc data` toggling is covered (`airplane-*`); the radio *dropping* a connection mid-request is a modem event | a rootable device with `svc` race conditions |
| Font scales beyond `large`, right-to-left locales, 120 % display size | `resConfigs "en"` and a `large`-only scale pass exist in the layout checks; the rest is not claimed | extend `LargeScreenRenderTest`'s matrix, which is JVM-side and cheap |
| Per-provider latency budget on flaky networks | The `Http` timeouts are pinned against a local server in JVM tests; `tc netem` shaping on the runner host is a bigger project | `tc` in a privileged container |

## What "100 %" means here, stated plainly

1. **Every user-visible flow above is driven on a real device image**, in the debug variant, in the minified
   release variant, at two screen sizes, and once with the radio off. That is the *behavioural* 100 %: no
   screen and no error path is unexercised.
2. **JaCoCo line coverage of `in.opentrack.india.*` is measured from those runs** and published per runner;
   the gate fails under the floor in `tools/ci/flow_coverage.py --min-lines`. This is the *quantitative*
   100 % and it is deliberately not claimed as achievable for every branch — hardware and OEM branches are
   in the table above.
3. **Anything the app shows on failure is asserted to be a sentence, not an absence** (`never-silent`,
   `airplane-track`, `screen-explains-itself`), because the two bugs that reached a real device in this
   project were both silence: an empty suggestion list, and a live column reprinting the timetable.

## Reading a red run that has no steps

A pipeline that dies before any step of the row runs has one readable channel, and it is not the log (those
URLs redirect to blob storage). The annotation is on the check run:

```bash
R=tarungittarun/Checktrain02; SHA=$(git rev-parse HEAD)
gh api "repos/$R/commits/$SHA/check-runs?per_page=100" \
  --jq '.check_runs[]|"\(.id) \(.name)"'          # find the row's check-run id
gh api "repos/$R/check-runs/<id>/annotations" \
  --jq '.[]|"\(.path) L\(.start_line) :: \(.message)"'
```

That is how the row jobs that failed in ninety seconds were explained - `Can't find 'action.yml' ... Did you
forget to run actions/checkout before running your local action?` - a message no amount of YAML parsing
locally would have produced, and the reason the job now does the checkout itself. `plan` posts its findings
on the commit for the same reason: a red gate should be readable without opening the UI.

## Reading a red row

The matrix writes one status line per row and posts it on the commit, so a failure says which of the three
things happened. They need different responses, and conflating them is how a harness becomes decoration:

| The row says | It means | Do this |
| --- | --- | --- |
| `boot=failed` | the runner could not host an emulator (no usable `/dev/kvm` for an x86 image), so **nothing was tested** | run the workflow on a KVM-capable runner — `ubuntu-latest` qualifies where it exposes `/dev/kvm`, otherwise any self-hosted box with `kvm`; the product is not implicated |
| `boot=ok` with `all=failed (no test summary…)` | `am instrument` died before reporting, usually an install or signature problem | read `/tmp/instrument-all.log` in the `e2e-result-<row>` artifact; the debug-signed test APK and the app must share a key, which is why CI signs the release APK with the debug keystore |
| `all=failed` with a JUnit stack | a real assertion failed on a real device | the same artifact holds `screenshots.tgz` and the `*.xml` window hierarchy for every assertion, tagged per flow |

Each row posts a comment on the commit even when it is green, led by PASS / FAIL / NO DEVICE and carrying
the boot seconds, one line per instrumented class (pass or fail, and how long it took), the coverage figure
and the monkey verdict — the value of a run is proportional to how much of it you can read afterwards.

Two rows run on an ordinary push (`api34`, `min24`); the density and release rows run on a
`workflow_dispatch` with the `full` checkbox, on a pull request, and on `main`. A dispatch also takes
`focus: TrackingFlowTest` to re-run one class in a few minutes instead of an hour, and `monkey_events: 0` to
skip the stability pass. That is a cost decision, not a coverage one: the flows are the same list, and `plan`
gates them on every event either way.

The same four knobs travel in a commit message, which matters because `workflow_dispatch` is not always
available to whoever is driving the pipeline (an integration token without `actions: write` gets a 403):

| marker | effect |
|---|---|
| `[e2e-full]` | all four device rows on a push |
| `[e2e-focus: TrackingFlowTest]` | one class on every row - a re-run in about four minutes |
| `[e2e-boot: 2700]` | raise the boot budget on a slow runner |
| `[e2e-monkey: 0]` | skip the random-event pass |

`plan` prints which it chose, and a `[e2e-*]` marker you did not write is harmless text. The message reaches
the step through an env var rather than an interpolation, so quotes or `$(...)` in a commit message cannot
execute anything. A dispatched run also gets its own concurrency group, so an unrelated push can no longer
cancel nine minutes of emulator boot - which is how two rows died on run 38126202814.

`report` turns the rows into a table and fails the run when *no* row booted (there is then no evidence
at all) or when the minSdk row's tests failed (the floor of the supported range is not optional). A modern
row failing to boot while another row ran the suite is recorded and left red-in-the-table rather than
red-in-the-build, because that row is a coverage question about new APIs, not about whether the app works.
