# Worklog — where this is at, and how to continue

Read this first, every time, before anything else. Update the three top lines before you stop.
It exists because CI knowledge is worthless if it lives only in the session that earned it.

## Head

- **HEAD / branch**: `arena/edd38b02-checktrain02` (push only here; one branch per session).
- **What the last commit changed (and why it took four rounds)**: the app now speaks the railway's own
  mobile enquiry protocol — `remote/NtesWire.kt` (AES-framed POST, cross-checked against a reference
  implementation's byte-exact vectors in `NtesWireTest`) and `remote/LiveProvider.kt:NtesProvider`, registered
  ahead of the timetable source. Until this commit every provider we had could only answer with the
  *schedule*, which is exactly the complaint: a screen of planned times dressed up as tracking. NTES answers
  with per-stop actual arrivals/departures, platforms, delays and its own last-update stamp, and it needs no
  key, no account and no server of ours — the crypto constants are inside the official app and shared by
  every client, so this satisfies "no keys, no proxies, no ads" rather than bending it. The timetable source
  stays in the chain as the labelled alternate, and a shape we cannot open yields a sentence on screen, never
  a silent dash. `Http.post` was added for it (deliberately uncached: caching a live-status answer is how a
  tracker starts lying about age).
- **New CI job `probe`**: a development sandbox is not allowed to reach `enquiry.indianrail.gov.in`, so the
  runner POSTs each sub-service and posts the *schema* (key names, how many rows carry actuals, the header
  fields) as a commit comment. Read that comment before trusting or rewriting the parser — `tools/ci/probe_live_feed.py`.
- **State of the device sweep** (as of `53399c4`): the run got further than any before — `plan` ✓, `build` ✓
  and all four rows (`api34`, `min24`, `density`, `release`) at step 5 of the row action 18 minutes in, i.e.
  inside the boot step. That run was superseded by the live-feed commit, which changes no harness code, so the
  next run answers the same question with the fix riding along.
- **Open questions the next run answers**: (1) does the flow suite execute on the booted emulator now that
  `adb` is on the job's `PATH` and `avdmanager` gets `-b/--abi`; (2) does `ShowFullRunJson` answer our envelope
  from a GitHub runner, and does the row comment from `LiveFeedFieldTest` report `reported > 0` — that number
  is the product claim, and "0 rows reported" with a note is the honest version of the same result.
- **Gate discipline** (learned the hard way): after *any* workflow YAML edit run
  `python3 tools/ci/test_check_workflows.py && python3 tools/ci/check_workflows.py`; a duplicated key inside a
  mapping once passed the old scanner and killed a run as an invalid file. `check_refs` no longer reads
  `x == y` inside a constructor call as a named argument, and `check_members` knows the JDK/org.json calls the
  crypto client makes — both were false positives, fixed in the checkers rather than by bending the app.

## What a real device told us, and what the app now does about it

The first field report from a phone on Indian LTE (train 56227, 18:36 IST) came back as:

```
providers PROVIDER_OVERRIDE=miss PROVIDER_NTES=miss PROVIDER=miss CACHED=miss
note LIVE · running status: The live feed refused the request (closed).
rows 40: 0 reported / 0 projected / 40 timetable
```

Read it precisely, because two different conclusions are available and only one is supported:

- **what is not wrong**: the parser. No response body ever arrived, so nothing was mis-parsed; and the screen
  said `OFFLINE · schedule only`, `no live fetch`, `no live feed` — the honesty machinery worked exactly as
  designed on a train with 40 halts.
- **what is wrong**: the request. `Http` reported the failure as *closed*, which is a server hanging up on us,
  and "closed" is all it knew: `e.message` without the exception class cannot separate a TLS handshake refusal,
  an HTTP 403 from a policy layer, and a carrier middlebox reset. Those need three different fixes.
- **what this does not prove**: that the device had a working route. The bundled timetable is on-device, so a
  schedule rendering is not evidence about the network — which is why the probe below includes a control
  against a host this app is known to reach.

So `remote/FeedProbe.kt` runs the request matrix **on the device** — the only vantage point that can — after a
refusal: seven shapes (shipped envelope, same over an explicit TLS 1.2 socket, browser agent, unencrypted
plain body, the legacy `liveStatusJsonNew` GET *with the Referer and X-Requested-With the NTES site has always
needed*, an independent mirror, and a control host). It is single-flight, 7 s per try, and it *adopts* the first
variant that yields a usable snapshot: `NtesProvider` asks with that shape from the next refresh, labels the
answer `via post-tls12` in the report, and stops if the payload ever stops being usable. Adoption is the reason
this is a fix rather than a telemetry exercise: on a network where only one shape answers, the app starts
working for that user without waiting for a rebuild.

Standing rule, restated because the pressure to bend it is coming: **no API key ships in this APK**, not even
a publicly documented sample credential. If the probe shows that only a keyed source answers from India, the
correct output is an evidence-backed proposal to the user (endpoint, what the key costs, what it means for the
no-accounts promise), not a quiet exception in the provider chain.

## What CI can and cannot prove about the live feed

`AppServAnd` at `enquiry.indianrail.gov.in` **refuses connections from GitHub's runner IPs**: two runs, three
trains, two sub-services each — six TLS/urllib timeouts in a row, reported by the `probe` job as a commit
comment. It is a geo/IP policy on the railway's side, not a bug in the framing (the request our client sends
is byte-identical to the reference client's, which is what `NtesWireTest` pins). Consequences worth knowing:

- a device row will therefore show the *transport failure* sentence and fall back to the labelled timetable,
  and that is the correct behaviour of a resilient chain — not a passing test of live data;
- the only instrument that can see the real payload is a device on an Indian network, i.e. the user's phone.
  Long-pressing the provenance badge on the tracking screen copies a report (sources tried, per-source
  outcome, how many rows carry a reported minute, the stop table as shown, the feed's own stamp); the same
  text goes to `logcat -s LiveFeedReport`. Paste it here and the parser gets pinned to reality in one round;
- so "the CI is green" and "the live feed works on your phone" are two separate claims. Do not accept the
  first as proof of the second, and do not read the second's absence from CI as a failure of the app.

## Reading a run from here (the only channels that work)

```bash
R=tarungittarun/Checktrain02
gh run list --workflow e2e.yml --branch arena/edd38b02-checktrain02 --limit 1 \
  --json databaseId,headSha,status,conclusion
gh api "repos/$R/actions/runs/<run>/jobs" --jq '.jobs[]|"\(.id) \(.name[0:24]) \(.status) \(.conclusion//"")"'
gh api "repos/$R/check-runs/<job id>/annotations?per_page=100" \
  --jq '.[]|"[\(.annotation_level)] L\(.start_line) \(.message[0:220])"'
gh api "repos/$R/commits/<sha>/comments?per_page=100" \
  --jq '[.[].body|select(startswith("### e2e"))]'
```

- Job logs redirect to blob storage and are **not fetchable here**. The check-run annotations are.
- Annotations are **not published until a step ends** — a quiet in-progress row is not necessarily stuck.
  The per-step `[row] step N:` notices exist so that the stream, once readable, says how far it got.
- `gh run cancel` and `gh workflow run` answer **403** for this credential. A push supersedes an in-flight
  run; `[e2e-full]` in the commit message selects the 4-row matrix, `[e2e-focus: Class]` one class.

## Solved — do not re-litigate

| Symptom | Cause | Where the fix lives |
|---|---|---|
| Run named by file path, `jobs: null`, ~25 s red | duplicate mapping key (`if: always()` twice) — PyYAML accepts it, Actions rejects the file | `tools/ci/check_workflows.py` |
| Same, for an action input | `required: true` **and** `default:` on one input | same checker |
| Job dies as one step, no sub-steps | `always()`/`failure()` in a **composite step** `if:` | verdict step reads `steps.<id>.outcome` |
| `Can't find 'action.yml'` | a `uses: ./local/action` needs the **job's** checkout, not the action's | `e2e.yml` row jobs |
| `exit code 127` mid-row, no other annotation | `adb`/`sdkmanager` not on the **job's** PATH (the boot script's export is local) | `$GITHUB_PATH` in `emulator_boot.sh`, visible `gh run download` fetch step |
| `Found AVD target architecture: arm` on x86_64 | hand-written AVD lacked `systemImage.arch` | `emulator_boot.sh` |
| `avdmanager create avd` prints its usage | needs `-b/--abi` named; global options go before the subcommand | `emulator_boot.sh`, three forms tried per run |
| Row comment never posted | `set -e` + `grep -q … && verdict=FAIL` aborts the step | `if` statements in the action |
| `1/1 passes green` on a row with no device | roll-up read `variant_row=` as a test | `e2e_rollup.py` (verdict-shaped values only) |
| False `rc=0` for every failed avdmanager form | an `if` with no `else` exits 0 | capture `rc` from the command |
| `jobs: null` right after editing an input | two `default:` keys on one input — and the linter blind to it | `dup_keys` rewritten, `tools/ci/test_check_workflows.py` runs first |

## Standing rules for edits in this tree

- After any script that rewrites `.github/**` by hand: `git diff --numstat` must show **additions with no
  deletions** unless the deletion is the point. One such script silently deleted 12 step bodies.
- Never chain an `assert`-guarded patch and a `git commit`/`push` in one shell command: a failed assert
  aborts the patch while the commit still runs.
- CI checkers are **stdlib-only**; PyYAML is optional and `check_workflows.py` degrades to indentation
  rules when it is absent (a runner's python has no PyYAML).
- Before pushing: `python3 tools/ci/test_check_workflows.py && python3 tools/ci/check_workflows.py &&
  `python3 tools/ci/flow_coverage.py &&
  for c in check_kotlin_balance check_refs check_resources check_members; do python3 tools/ci/$c.py; done`
  and `bash -n` on every harness script. All must be 0 problems.
- Keep the workspace lean: no generated artifacts, no scratch dirs in the repo, nothing outside it.

## What the pipeline is for

The app (offline timetables, live feed stamped and labelled, never presenting schedule as actual) is built by
`.github/workflows/android.yml`; `.github/workflows/e2e.yml` builds the APKs and then **drives them on
emulators on four parallel runners** across the 46 flows listed in `docs/E2E_FLOWS.md`, with the manifest
gated against `@flow` annotations on every event. `report` fails the run when no row booted or the minSdk
row's tests failed, and ratchets line coverage (`--min-lines 45`) — to be raised once the first green device
row produces a JaCoCo number.
