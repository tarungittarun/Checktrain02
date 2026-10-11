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
| T9.5 | C15 live station-pair search — Stage 0 PoC now lives inside `tools/ci/probe_live_feed.py` (extended, not forked): probes `FindTrainJson` + `TrainBtwStnJson` for the pairs/trains in `fixtures/known_truths.json`, **and** `erail.in` GET for the same pair as the keyless alternate; captures raw bodies to `PROBE_CAPTURE`; prints a *proposed* refresh of the truths fixture that a human must apply | **DOING — decision branch resolved: brief 7.7(a)** | CI probe comment on `ac06f62` (2026-10-12 IST): **erail.in HTTP 200 from a runner** — identity answers for 16228/16226/16221/12627 (575–986 B each) + SMET→MYS pair list 2976 B with 17 train numbers; **NTES TLS-timeout on all 13 calls** (4th independent confirmation). Runner-side pair truth is therefore CI-refreshable and C15 gets a parser written against a *captured* body (4.5) | commit the captured erail pair body as a small dated fixture (2.5), then `v2/source/PairSource` + erail parser against it, JVM-tested; capture arrives via the `PROBE_CAPTURE` artefact of the next `probe` job (download it with `gh api repos/tarungittarun/Checktrain02/actions/runs/<id>/artifacts`) | no |
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

## Round 13 — new session branch, kickoff actions 0.1–0.5 (2026-10-11 UTC)

Session pinned to `arena/508929e1-checktrain02`, branched from `d8da0c5` (v2-fresh tip = the owner's merge of
PR #1). Actions 0.1–0.5 of the new brief (now `docs/v2/KICKOFF.md`) executed before any feature work.

| id | micro-task | state | verified-by | next action | needs-device |
|---|---|---|---|---|---|
| T13.1 | Brief saved as `docs/v2/KICKOFF.md` (action 0.1) | **DONE** 2026-10-11 | commit `85a9c2c` | — | no |
| T13.2 | Refs confirmed + v2-fresh merged (action 0.2): `main=9a0514d` untouched, `origin/v2-fresh=d8da0c5`, prev-session head `ac06f62` still on `arena/edd38b02-checktrain02`, tags `v2-m1=67e7e02`/`v2-restart=52cd940` fetched; session branch tree is **identical** to `ac06f62` (owner merged the contract via PR #1), so the merge is a no-op. `3e795a5` (HANDOFF) not visible anywhere → section 12 used, as the brief instructs | **DONE** 2026-10-11 | `git ls-remote` + `git diff --stat HEAD ac06f62` (empty) | — | no |
| T13.3 | CI verdicts for `ac06f62` and `67e7e02` read from the API (action 0.3) | **DONE** 2026-10-11 | see "Round 13 CI verdicts" below | — | no |
| T13.4 | Probe comment read (action 0.4) → brief 7.7 branch **(a)** adopted: erail reachable+200 from CI | **DONE** 2026-10-11 | commit comment `### live-feed probe` on `ac06f62`, probe job run `114551799681`/`114551812161` success | — | no |
| T13.5 | **16221 identity contradiction, stated per 17.4, not applied**: erail live answer says `16221 = TLGP→MYS KUVEMPU EXPRESS`; the owner-verified fixture says `16221 = KAVERI EXPRESS MAS→MYS`. Owner-verified outranks one keyless source, so the fixture stands and erail's claim is recorded here as a contradiction (erail also lists 16221 inside the SMET→MYS pair answer, consistent with its own TLGP→MYS identity). `must_not_include` stays empty (7.3) | **HELD — waiting on one word** | probe comment on `ac06f62` vs `fixtures/known_truths.json` | owner re-checks 16221 in Where Is My Train: **KUVEMPU or KAVERI?** (one word decides; then the fixture edit is applied deliberately, not auto-) | no — answerable from the phone they already own |
| T13.6 | Erail pair capture → committed fixture: download the `PROBE_CAPTURE` artefact from the `ac06f62` probe job, save the SMET→MYS body (2976 B) as a small, attributed, dated fixture (2.5) | **NEXT** | — | `gh api repos/tarungittarun/Checktrain02/actions/runs/<probe-run-id>/artifacts` | no |
| T13.7 | `tools/ci/known_truths_refresh.py` — the PRINT-ONLY refresher of 7.7(a), with its own test (4.4: a tool that answers a question gets a test that it asks it) | **NEXT** | — | — | no |
| T13.8 | C15 = M2: `v2/source/PairSource` + erail pair parser **written against the T13.6 capture only** (4.5), typed failures, `PairListing` out, JVM tests per 8.2 states; then merge-into-results, overrides (7.8), TTL cache | **NEXT** | — | starts only after T13.6 lands | no |

### Round 13 CI verdicts (read from the API, 19:35–19:45 UTC)

- `ac06f62` (v2 Stage 1 contract — the commit that had never met a compiler): `checks` **success**,
  `build` **success** (runs `114551833357`/`114551851788`), `debug APK` **success** (`114551709681`/`114551716481`),
  `tests + screens (Robolectric)` **success** (`114551735835`/`114551749053`), `probe` **success**
  (`114551799681`/`114551812161`), `plan` **success**, `release`/`e2e_full` skipped (tag-only / gated),
  emulator rows **in_progress at report time** (`114552502517`, `114552502539`, `114552502561`, `114552502581`,
  `114552784775`, `114552784892`; started 19:16–19:18 UTC). So the contract DID compile and its unit tests DID
  run green — action 0.3's worry is answered by the run, not by hope. The emulator rows were still flying when
  this round pushed (different branch ref, so the push cannot cancel them — concurrency is keyed on
  `github.ref`, checked in both workflows).
- `67e7e02` (v2-m1 tag): `report` **failure** — root cause read from the check-run annotation (job logs are on
  blob hosts this sandbox cannot reach): **"no e2e row reported anything - the matrix did not run"** — the e2e
  rows on that commit were `cancelled` (the round-9 double-push, lesson 13), and the rollup honestly refused to
  claim anything. Not a new defect; the honest-failure behaviour working as designed. The fix is cadence, not
  code: one push per round, which this round obeys.
- Sandbox limit found while reading logs: `gh api .../jobs/<id>/logs` and `--log-failed` return EOF from here
  (log storage hosts are outside this sandbox's egress allowlist). Verdicts are read from check-runs +
  annotations, which do arrive via `api.github.com`. Recorded so the next session does not burn a round on it.

### HANDOFF (round 13 state, for any session that resumes)

Resume line: **first DOING is T9.5 → its first NEXT child is T13.6** (erail capture → fixture), then T13.7,
T13.8. Open owner question: exactly one (T13.5, one word). The previous session's emulator rows may land after
this round's report — read them before starting M2 implementation, since they are the last untested axis
(API 24 floor, density, release/R8) for the tree this session inherited.

*(T13.5 answered and the T13.6–T13.8 work happened in round 14 below; the round-13 HANDOFF text is kept
verbatim because the audit trail matters more than tidiness.)*

## Round 14 — owner word applied, capture mechanism shipped, C15 scaffold (2026-10-12)

Owner answered the round-13 question: **16221 is the KUVEMPU EXPRESS** — erail's live answer was right and the
fixture's KAVERI MAS→MYS assertion was wrong. Everything this round follows from that plus brief 7.7(a).

| id | micro-task | state | verified-by | next action | needs-device |
|---|---|---|---|---|---|
| T14.1 | 16221 corrected to `KUVEMPU EXPRESS TLGP→MYS` in `fixtures/known_truths.json`, by owner word (chat, 2026-10-12), seconded by the erail live answer; `verifiedOn`→2026-10-12; correction provenance written into the note so no tool re-litigates it | **DONE** | fixture diff in this commit; the ETL re-runs the gate in CI (build stays green per 7.4) | read the gate's new verdict line for 16221 in the next asset build log | no |
| T14.2 | Capture mechanism (4.5's "deliverable is the capture mechanism"): e2e probe job now sets `PROBE_CAPTURE`, uploads a `probe-capture` artefact, and posts the **raw erail pair body as a commit comment** (artefact hosts are unreachable from the sandbox — the comment is the sandbox-readable path); `probe_pair` returns the body verbatim | **DOING — proven locally, CI verdict pending** | `test_probe_live_feed.py` 14/14 green incl. the capture-comment test; probe job of this push is the first that captures | next round: read the `### live-feed capture` comment on this push's probe job → commit the SMET→MYS body as a small attributed dated fixture (2.5) | no |
| T14.3 | `tools/ci/known_truths_refresh.py` — PRINT-ONLY refresher: per-fact AGREE / CONTRADICTION / UNVERIFIABLE, contradictions flagged with both values (never applied), dead network deletes nothing; 9 tests incl. an AST guard that it contains no write-mode open | **DONE** | `test_known_truths_refresh.py` 9/9 green locally; wired into `checks` job; sandbox smoke run printed all-UNVERIFIABLE honestly (erail blocked here) | the weekly job (15.6) pastes its report | no |
| T14.4 | DEFECT (tooling, caught before a phone): the erail identity parser read `fields[2]/[3]` as station codes — an imagined shape — so **every CI identity answer was silently rejected** ("station fields parsed as 'TLGP MYS EXP'/'TALGUPPA'"). Rewritten anchored on the `^number` field, tested against the four REAL records from the ac06f62 probe comment | **DONE** | `ErailIdentityParser` tests (real 16221 + 12627 records verbatim); the defect was caught BY writing the test from the capture first | next CI probe should record identities in its proposal — verify, it is the regression proof | no |
| T14.5 | v2 layout per 5.1: contract types moved to `v2/contract/` (Provenance/Facts/ContractTest, packages updated); new `v2/source/PairSource.kt` — injected transport + injected parser, one query, no retries, typed `PairAnswer` = Listing / AnsweredEmpty / CouldNotLook / ProtocolChanged / DisabledByPolicy (the 8.2 states, distinct types); `PairSourceTest` 7 cases | **DONE (scaffold)** | local: `check_refs` 117 types 0 problems, all Kotlin gates green; CI: this push's `tests + screens` job runs the 7 new cases (real parser deliberately absent until the capture lands, 4.5) | read the Robolectric job verdict on this push | no |
| T14.6 | Erail pair parser (real) written against T14.2's captured body + committed capture fixture; then merge-into-results with live-wins-while-fresh, overrides (7.8), TTL cache, UI copy per 8.2 | **NEXT** | — | unblocks the moment T14.2's capture comment exists | no |
| DEFECT-1 | **Release e2e row FAILED on `ac06f62`** (R8 + resource-shrunk APK): annotations read "ReleaseSmokeFlowTest=failed (instrument exceeded 600s; the device may have wedged)", "ColdStartFlowTest=failed (instrument exceeded 600s)", "no coverage file came off the device". Root cause NOT yet readable: job logs + the `e2e-result-release` artefact sit on blob hosts this sandbox cannot fetch, and the row's promised commit comment never posted. It is exactly the 5.5 axis (resource shrinking has bitten us before) but R8-breakage is NOT asserted - a wedged 2-vCPU runner is equally consistent with both tests timing out together | **BLOCKED: evidence on an unfetchable host** | check-run `114552502539` annotations, quoted above | (1) read this push's release-row verdict (second data point: same wedge twice = R8 suspicion rises; pass = runner noise); (2) if it fails again: owner opens the `e2e-result-release` artefact of run `38167065338` (browser fetch works where the sandbox cannot) and pastes the logcat tail | no (owner paste only if it repeats) |

Round-14 CI verdicts and the previous session's emulator rows are appended to the PR (#2) body thread as read.

## Round 15 — CI red read and fixed, capture landed, real erail parser (2026-10-12)

The round-14 push went **red** and the run was read before anything else: `build`/`debug APK`/`tests + screens`
FAILED (runs of `ae7ae56`), `checks`/`plan`/`probe` passed. The compiler found what no local gate could.

| id | micro-task | state | verified-by | next action | needs-device |
|---|---|---|---|---|---|
| DEFECT-2 | **Compile red, mine**: `PairSource.kt`/`PairSourceTest.kt` imported the contract from `in.opentrack.india.v2` after the SAME commit had moved it to `...v2.contract` — written against the old layout and never re-pointed. The commit-comment posted the 8 errors verbatim; fix = import path only | **DONE** (fixed this round) | `### build failed` comment on `ae7ae56`, quoted in the round report; this push's `build` job is the verdict | — | no |
| T15.1 | **The gate that missed it is fixed**: `check_refs.py` matched simple names only, so a moved-package import passed while the type existed anywhere. New import-package rule: every `in.opentrack.*` import must name a package some declaration actually uses (any-site match — `SourceKind` legitimately exists in BOTH `in.opentrack.india.model` and `...v2.contract`); multi-root scan so test imports resolve against main; `test_check_refs.py` (4 cases, first one reproduces the real miss, failing-first per 8.7); `checks` job now runs it and scans main+test roots | **DONE** | self-test green; combined real-tree scan `56 files / 139 types / 0 problems` | — | no |
| T15.2 | **The capture landed** — the T14.2 mechanism worked on its first CI run: `### live-feed capture (erail pair, raw body)` posted on `ae7ae56`, 2976 B verbatim, 4 `^`-anchored records (16228/16205/16226/16221) | **DONE** | commit comment on `ae7ae56`; body committed byte-exact as `app/src/test/resources/captures/erail-pair-SMET-MYS-2026-10-12.txt` | — | no |
| T15.3 | **`ErailPairParser` written against the capture only (4.5)**: `^number`-anchored records, header pair codes + feed stamp, times `HH.MM`→minutes with midnight wrap as day offset, unparseable records counted in the note (never silent), header-with-no-records = NothingRunning, garbage = Unreadable(sample). 10 tests driven by the real values — including 16221 KUVEMPU present on SMET→MYS (the pair our dump misses) and boardable-first ordering on live data at 11:40 | **DONE (scaffold complete: transport+parse proven, UI wiring next)** | `ErailPairParserTest` rides this push's `tests + screens` job | read the job verdict on this push | no |
| T15.4 | **erail pair feed stamp measured: `2026-8-6-21-30-34`** — the pair feed is TIMETABLE-vintage (6 Aug), not minutes-fresh; identity feeds stamp daily. Consequence: erail pair is "a fresher timetable" (Aug 2026 vs our 2022-era asset), the row label stays `via erail` (our fetch is fresh) and the data's own date travels in the listing note verbatim. 15.7's demotion trigger ("its own stamp is older than its stated cadence") does NOT fire — a timetable feed's cadence IS the timetable cycle; the weekly report tracks stamp age from now on | **DONE (measured, recorded)** | the capture itself; `ErailPairParserTest.theFeedsOwnStampTravelsInTheNoteNotDressedUpAsFreshness` | weekly job graphs stamp age | no |
| T15.5 | Wire `ErailPairParser`+`PairSource` into search: real erail transport (plain HTTPS, one attempt, budget), live-wins-while-fresh merge with the asset labelled per row, TTL cache + overrides (7.8), UI copy for every 8.2 state, then the emulator proves it against the capture served by the mock | **NEXT** | — | starts when this push's jobs are green | no |
| DEFECT-1 (update) | On `ac06f62` the **density row failed too** (release + density both; api34/min24 still in flight at round time — 2 of 4 rows red). Wedge-vs-R8 still undistinguished. This push's commit message carries `[e2e-full]`, so the full matrix runs on the SAME tree as the compile fix: two more data points, evidence still unfetchable from the sandbox | **OPEN** | check-runs of `ac06f62` | read this push's release + density rows; if red again, owner pastes the `e2e-result-release` artefact | no (owner paste only if it repeats) |

Round-14 verdicts for the record: `ae7ae56` checks/plan/probe success, build/debug APK/tests+screens failure (DEFECT-2), e2e skipped (downstream of build), report honest-failure. Previous session's api34/min24 rows: still in flight at round close.

## Round 16 — the owner asked two questions and both got numbers (2026-10-12)

Owner: *"workflows are running for too long — required, or problems?"* and *"many workflows executed, many
artifacts generated — keep artifacts shorter and handle the earlier ones."* Answered with the run list and the
artifact store, then fixed at the root. **ADR-001** records the decision.

| id | micro-task | state | verified-by | next action | needs-device |
|---|---|---|---|---|---|
| T16.1 | **Why everything ran twice**: `pull_request` triggers mirrored every push (the branch sits open as PR #2), and `plan` gave PR events the FULL 4-row emulator matrix - so each push was executed twice, the copy being the expensive one. Fix: `pull_request` removed from both workflows; full matrix now only via `[e2e-full]`, dispatch checkbox, or main | **DONE (ADR-001)** | run list quoted in ADR-001 (5 pushes ≈ 20 runs, half redundant); this push must show **2** runs (android+e2e push events), not 4 | count this push's runs | no |
| T16.2 | **Why artifacts piled up**: 385 artifacts in one day; `apk-debug`/`apk-release`/`e2e-classes`/`e2e-result-*` had NO retention-days → 90-day default. Fix: every upload now names 3-7 days; `opentrack-debug-apk` 30→7 (the installable lives on the `latest-apk` release, which is not an artifact and is untouched) | **DONE (ADR-001)** | workflow diffs in this push; retention matrix in ADR-001 | — | no |
| T16.3 | **Handle the earlier pile**: the sandbox token can LIST but not delete/cancel - `POST /runs/{id}/cancel` and `DELETE /artifacts/{id}` both 403 "Resource not accessible by integration" (measured this round). So the cleanup lives in CI: new `hygiene` job (android.yml, `actions: write`) runs `tools/ci/artifact_hygiene.py` every push - deletes artifacts >6 h old, cancels runs `in_progress` >4 h as wedged (untested-with-reason, 6.2), one ::notice:: line with totals | **DONE (policy) — first real run is this push** | `test_artifact_hygiene.py` 4/4 (classification + orchestration, frozen clock injected); the 403s quoted in ADR-001 | read hygiene's ::notice:: annotation on this push: expect the ~380-artifact pile deleted | no |
| T16.4 | **Is the CI required at all?** Answered in the round report: what each job proves (checks=static gates+tool self-tests; build=the APK the release serves; ui=Robolectric+screen previews; probe=the live-feed answers that decide the data strategy; e2e rows=device proof; report=the honest roll-up). The waste was structural (doubling + 90-day defaults), not the jobs themselves | **DONE** | round report on PR #2 | — | no |
| DEFECT-1 (update) | The two wedged `ac06f62` e2e rows (>80 min, superseded tree): sandbox cancel returned 403, so they are left to hygiene's 4 h guillotine - their verdicts will never arrive, recorded as untested-with-reason. The live second data point is this round's previous push (`a446511`, `[e2e-full]`, push event) | **OPEN** | hygiene notice when it fires | read `a446511`'s release/density row verdicts next read | no |

## Round 17 — M2 slice 1: the live pair search is wired into the screen (2026-10-12)

Owner instruction set the cadence: deliver the working APK, don't stop for updates, work while CI flies.
This round built the whole vertical slice: the search screen now asks erail once per pair search, merges the
live listing over the asset rows, labels every row (`via erail` / `timetable`), and says the 8.2 sentence
under the header in every state.

| id | micro-task | state | verified-by | next action | needs-device |
|---|---|---|---|---|---|
| DEFECT-3 | **CI caught a real semantics bug (a446511/10df361 Robolectric red)**: a pair body whose only record we could not read returned `NothingRunning` - our blindness dressed up as "the railway runs nothing", the exact 2.3 conflation. Fixed: rows empty + unread records → `Unreadable` with a sample; only a VALID empty header is `NothingRunning` | **DONE** | `### tests / screens failed` comment (1 failing of 170); fix + renamed failing-first test in this push | read this push's Robolectric verdict | no |
| T17.2 | `v2/domain/PairCache` (TTL = Freshness.LIVE_PAIR, injected clock, empty answers never cached, capacity-bounded) + `v2/domain/PairMerge` (selection: fresh live list is the authority for WHICH trains run, asset-only rows stay labelled `timetable` (7.3), live-only rows appended boarding-sorted; order: one decision per source) + `v2/source/ErailTransport` (one HTTPS GET, budget, typed failures, capped body, injected opener) - 17 new JVM tests | **DONE** | local: all 11 gates PASS; CI: this push's Robolectric job | read the Robolectric verdict | no |
| T17.3 | **The screen (M2 slice 1)**: `MainViewModel.search` now fires one live erail attempt after the asset render (cache-first per 5.6), `applyLive` merges via `PairMerge` and posts rows with `sourceTag` (`via erail`/`timetable`) + the state line; `MainActivity` composes header = asset sentence + live note; rows without a readable time are COUNTED in the note, never rendered as midnight (2.4); km hidden (renderer's `>0` guard) so no fabricated distance | **DONE (pending CI + one device look)** | the four v1-file touches are each justified under 5.2(b) in the commit message (the results screen is v1's; the strangler replaces screens one at a time) | install the next green `latest-apk`, search SMET→MYS: expect rows labelled `via erail`, incl. 16221, and the live-state sentence | **yes** (the M2 owner check) |
| T17.4 | **Blunt self-criticism, second instance of the same lesson**: round 16's edit reported the hygiene job "added" but it never landed - the only `hygiene` in the committed file was the tests step. The run of `10df361` proves it (jobs: checks, debug APK, ui, release - no hygiene). Re-inserted properly, verified by grep (3 occurrences) + workflow gates. The lesson's text was already in KICKOFF 5.8; now it has cost a round twice | **DONE** | `grep -c hygiene` = 3; jobs list of this push's android run must include `hygiene` | read hygiene's first real ::notice:: | no |
| T17.5 | The `10df361` e2e ran the FULL matrix (all four rows) although `[e2e-full]` was on `a446511`'s message, not its own - one extra ~2h matrix spend. Not yet explained; both candidate causes (PR title in HEAD_MSG concat; a446511's cancelled run's check-runs bleeding into the listing) are checkable | **NEXT** | run `38173086616` + its `plan` step output | `gh api repos/tarungittarun/Checktrain02/actions/runs/38173086616 --jq .event` and read the plan step log next round | no |

## Round 18 — reds read and fixed, the conversion made pure (2026-10-12, continuous)

| id | micro-task | state | verified-by | next action | needs-device |
|---|---|---|---|---|---|
| DEFECT-4 | `3b7a14d` red ×3, read and fixed in `f01985c`: (a) `PairCache.Entry` (nested class) referenced the outer cache's `ttlMillis` - a bare-name outer-scope resolution NO lexical gate can see, so the compiler was the only reviewer (third instance); the TTL is now a parameter, the more honest signature; (b) hygiene's first run failed because `gh` needs GH_TOKEN in the environment - added `github.token` + the job's `actions: write` | **DONE** | build-failed comment (1 error, quoted) + hygiene annotation ("To use GitHub CLI in a GitHub Actions workflow, set the GH_TOKEN environment variable") | read `f01985c`/`ae16f4e` verdicts | no |
| T18.1 | **Overnight-row bug found by inspection before any run**: the v1 conversion rendered 16228 (23:00→08:20 +1) with duration −880 and no "arrives next day". `PairMerge.toTrainHits` now converts to v1's CUMULATIVE minute convention (+1440), is pure JVM, tested (symptom-named test per 8.7), and `applyLive` delegates to it; cache-served answers say "cached, fetched N min ago" (7.5) | **DONE** | `PairMergeTest` (3 new cases incl. the symptom); pushed as `ae16f4e` | read the Robolectric verdict on `ae16f4e` | no |
| T17.5 (update) | `10df361` e2e ran the full matrix on a `push` event whose message had no `[e2e-full]`. Suspects: stale workflow file version for push events, or the cancelled predecessor's rows bleeding into the SHA listing. Plan-step logs are blob-hosted (sandbox-unreadable) | **HELD: evidence unfetchable** | run `38173086616` event = push (API) | observational probe: read the e2e job count of the CURRENT push (f01985c/ae16f4e) - 2 rows = fluke, 4 rows = systematic and needs the plan log pasted by the owner | no |

