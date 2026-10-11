# STATUS — resume here, do not restart here

**Protocol.** The first `DOING` line, else the first `NEXT` line, is where work continues. A `DONE` line is
re-opened only when its `verified-by` artifact fails — never by curiosity, never because a new round forgot it
existed. State changes land in the same commit as the code they describe. `needs-device: yes` is the only
legitimate reason to ask the owner to install an APK.
Branch note: this session is pinned to `arena/edd38b02-checktrain02` by the harness, so the "fresh branch" the
owner asked for is realised as this `docs/v2/` restart on this ref. Do not create other branches — they are not
tracked and the work would become invisible.

## Round 9 — data truth as a first-class object

| id | micro-task | state | verified-by | next action | needs-device |
|---|---|---|---|---|---|
| T9.1 | Known-truths gate: `fixtures/known_truths.json` + `tools/ci/data_truths.py` (the repo's only check against reality) | **DONE** 2026-10-11 | standalone run exit=1, 5 FAILs on today's asset; `report()` output in step summary | extend with direction-aware pair check (T9.7) | no |
| T9.2 | ETL runs the gate and stamps `meta.data_trust` + `data_truth_problems` | **DONE** | `python3 tools/build_refdb.py --profile halts --out /tmp/refdb.dbgz` → `meta.data_trust = 'unverified'` | — | no |
| T9.3 | Chip/Settings stops dressing stale data in a date: `dataVersion()` has verified / never-checked / UNVERIFIED states | **DONE** | string assembled from real meta: `6,775 trains · timetable UNVERIFIED: numbers/names disagree with the live railway data of 2026-06-20 - verify before travelling` | see how it fits the chip in a screenshot after T9.4 | no |
| T9.4 | CI verdicts per commit | **PARTIAL - read, not assumed** | `a06ac16`: android run `38160764951` **success** (so the ordering fix + new tests + vintage key compile and pass, and its APK is what `latest-apk` serves). `52cd940`: android run `38162949280` **cancelled by my own next push** - no build evidence. `7e444f5` (shape capture): android `in_progress`, e2e pending at the time of writing. The `report: failure` row on `a06ac16` is that cancelled e2e run's rollup (`--log-failed` empty), not a product failure. | read `7e444f5`'s run next round; do not push until it lands (lesson 13) | no |
| T9.5 | C15 live station-pair search — Stage 0 PoC now lives inside `tools/ci/probe_live_feed.py` (extended, not forked): probes `FindTrainJson` + `TrainBtwStnJson` for the pairs/trains in `fixtures/known_truths.json`, **and** `erail.in` GET for the same pair as the keyless alternate; captures raw bodies to `PROBE_CAPTURE`; prints a *proposed* refresh of the truths fixture that a human must apply | **DOING** | local run proves the harness + correctly reports `TLS closed` from this sandbox (as CI did on da0633c/b41c38c) — so the honest verdict so far is `blocked-by-network`, which is itself a Stage-0 exit answer | read the CI probe comment on the pushed commit: if erail answers from a runner, pair-truth becomes CI-refreshable and C15 gets a parse test the same round; if both are blocked, C15 is device-only and `needs-device: yes` becomes true for exactly one capture | no |
| T9.7 | Gate hardening: `must_not_include` (phantom trains) + direction-aware wording | **DONE** | gate runs clean on today's asset, 3 FAILs still reported; new check is schema-ready | see T9.7a | no |
| T9.7a | First phantom assertion (06228 on SMET->MYS) | **HELD — deliberately unasserted** | `fixtures/known_truths.json::_pending` explains why: 0-prefix is still used for specials, so 'retired number' ≠ 'does not run' | needs one live pair answer; do not guess to make a test green | no |
| T9.6 | C3 `NtesWire.parse` against a real payload | **DOING — capture shipped** | `NtesWire.shapeOf` + `Diary` lines on both NTES failure paths (unparseable AND parsed-empty, which was silent); gates clean | next build → owner opens 16228 with logging on → export; the `shape=` line decides the key-name question without any body leaving the phone | **yes** (log export only, no install) |
| T9.6a | The uploaded diary never reached the sandbox (`/home/user/uploads` absent, twice) | **BLOCKED: transfer, not analysis** | `find` in the sandbox found nothing | if a diary is re-sent, look for `[TLS]`, `[NET]` and (after the next build) `shape=` | no |
| T9.7 | Gate hardening: assert `from→to` presence **and** absence of phantom trains, per pair, direction-aware | **NEXT** | — | add `must_not_include` + reverse-direction detection to `data_truths.py` | no |
| T9.8 | C14 self-healing store: `train_overrides(train_no, source, fetched_at, payload)` with TTL; live wins while fresh | **PLANNED** | schema v4 migration test | design after T9.5 proves a live response exists | no |
| T9.9 | Emulator runs the **installed-shaped APK against fixtures** (mock at `10.0.2.2`) so parse+UI+states are CI-proved | **PLANNED** | new `e2e.yml` row + golden screenshots | start with one flow (pair search → detail) on API 34 | no |
| T9.10 | Housekeeping: dead `R.string.data_as_of` and now-unused `RailTime` import removed | **DONE** 2026-10-11 | grep proved each was referenced exactly once (its own definition); `check_resources` + `check_refs` clean | — | no |
| T9.11 | Results-screen staleness line (repo has no `repo` accessor in `MainActivity`; needs a state field) | **DEFERRED** (round 8, deliberately) | — | lands with T9.3's follow-up when `MainViewModel` exposes it | no |

## Owner's words that outrank any plan above

- *"most of the train showing here are incorrect… even train numbers are incorrect… I need the latest and running info"*
- *"build a new branch… exact correct approach… step by step and in best methodology like how actual product development happens"*
- *"why cannot you test the final apk with expected functionality in emulators… Everytime I can't install the apk"*
- *"most importantly is not final apk, it's the each component… their correctness and consistency, compatibility, and end user experience… as a owner of a PRODUCT"*
- *"have track status in each of you micro task… so that if you are freezed somewhere… you should continue from where left, instead of repetition"*

## Latest verified device state (so nobody re-derives it)

Owner's phone, 23:20 IST 2026-10-11, `motorola edge 50 neo` / Android 16, Wi-Fi + 5G up:
our app on 16228 shows `SMET → SBC · 274 km`, `Delay: no live feed`, every row `—  timetable`,
`Next: Birur Jn · 23:40 due · in 21 min`, chart captioned "amber dotted = what the timetable implies… not a
report about the train" (labelled correctly ✓), while Where Is My Train shows `16228 Talguppa - Mysuru Express
(TLGP-MYS)` with per-stop actuals + `Platform 1` and `0 km to Bhadravati · Updated few seconds ago`.
Installed APK is `7f84cd6`; `latest-apk` currently serves `71fa6bd`.

## Push discipline (new, from lesson 13)

One push per round. `docs/v2/*` and the shape-logging commit are **committed locally and deliberately not
pushed** while run `38162835…`/`7e444f5` is in flight; they go with round 10's code so that a build's evidence
is never cancelled by impatience. Resume rule: check that run's conclusion first, then push.

## Branch decision (owner asked for a new branch, 12 Oct)

The harness pins this session to `arena/edd38b02-checktrain02`; I may not create or push another branch, and
work on an untracked ref would be invisible to the owner's review. What was done instead: the fresh start is
structural (`docs/v2/` restart + live-first architecture + new gates), and the restart point is tagged
`v2-restart` so the owner can branch a clean lineage themselves in one command:
`git checkout -b v2-fresh v2-restart` (or open a PR from the arena branch).

## Round 10 CI verdicts (read from run 38165202? no - from the API, 00:09-00:20 IST)

- `19fb583`: `checks` **success**, `debug APK` **success**, `tests + screens (Robolectric)` **success**,
  `probe`/`plan`/`build` **success**, emulator rows in progress. So the shape-capture and v2 docs compile into
  a shippable APK; `latest-apk` now carries the ordering fix, the vintage key and the UNVERIFIED wording.
- **Stage 0 reachability answer, in the probe's own comment:** from a GitHub runner, `enquiry.indianrail.gov.in`
  times out on the TLS handshake for all 12 calls (third independent confirmation: da0633c, b41c38c, now this).
  So CI cannot refresh pair-truth from NTES, and `TrainBtwStnJson` from a phone is the only live route left.
- **Bug the probe found in itself:** the erail alternate never ran (gated behind the primary's `continue`), so
  the "second witness" was fictional. Fixed by extracting `probe_pair()` with injected transports +
  `tools/ci/test_probe_live_feed.py` in the `checks` job. The erail-from-runner answer is therefore still
  pending - it arrives with the next probe comment, which is the first one that actually asked.

## Round 12 — the freeze is in force, and Stage 1 has a spine

**Owner instruction: "just focus on fresh start."** Applied as policy, not sentiment:

- **Legacy freeze.** No new features, refactors or cosmetics in `ui/`, `remote/FeedProbe.kt`, `core/BrowserFetch.kt`
  or `core/Diary.kt`. Those files may change only to (a) fix a blocking defect or (b) expose something v2 needs,
  and each such change must say here why it could not be done inside v2. Everything additive lands in
  `in.opentrack.india.v2`.
- **v2 is written forward, not ported.** A v1 component is inherited only by name in `COMPONENTS.md` with its PoC
  still passing; otherwise v2 grows its own. The accumulated probe ladder stays a legacy diagnostic, not an
  architectural input.
- **Stage 1 started**: `v2/Provenance.kt` (SourceKind / Provenance / Freshness) + `v2/Facts.kt` (TrainIdentity /
  RouteStop / PairListing) + `ContractTest` (12 tests). The contract is the *fix* for the class of bug that
  started this: provenance is a required constructor argument, so a time cannot reach a screen without saying
  where it came from, and "no answer" cannot be rendered as "no trains".
  Deliberately pure JVM (no Android, no SQLite, no HTTP) so the rules are argued with failing tests rather than
  screenshots. **Not yet compiled by anything but the local balance/member gates — CI decides, as always.**
