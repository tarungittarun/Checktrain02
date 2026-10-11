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

