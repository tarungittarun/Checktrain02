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


## The opt-in diagnostic log, and why the second field report was from a stale build

The third device paste looked like a re-test of the fix and was not one: it still listed `get-howtherail`, a
variant deleted in the previous commit, and its GET lines still lacked the exception class that commit added.
Same words, older build. Nothing about that is the user's fault - a rolling release asset is easy to believe
is current - but it cost a round trip, so the report now starts with the truth:

    build 1.0.0 code 53 · diagSchema 1 · log off

A report that cannot say which build wrote it is a rumour. That line is also why `flow_coverage` and the
`checks` job must never be the only gate: `117a465`'s probe shipped a control host (`howtherail.in`) that
does not resolve anywhere, and every gate was green.

### What was built instead of another screenshot request

`core/Diary.kt`, driven from the Data source screen (the one screen the user had already found):

* **Off until switched on.** `log()` returns before it touches the buffer or the disk. No shadow capture that
  merely has not been flushed - the first byte of the file exists because the user asked for it, and the
  screen says how many bytes and when the last entry was written, because "I enabled it and nothing happened"
  is otherwise indistinguishable from a broken switch.
* **Content by rule, not by taste.** Host and verdict per request attempt, provider verdicts, parse refusals
  with only the JSON *key names*, the probe's matrix. No query strings (they carry the train number and the
  travel date), no response bodies, no locations, no accounts. A redaction pass drops `app_key`/`api_key`/
  `token`/`secret`/`password`/`sckey` values and any bare 32-hex digest, and there is a unit test asserting
  each - so the pass cannot quietly rot while new fields are added around it.
* **Egress facts the app cannot otherwise learn.** On enable: device/OS, transport (incl. whether a VPN is
  the active route), whether Private DNS is `off`/`opportunistic`/`hostname` - with the resolver reduced to
  its last two labels because those hostnames embed account ids - and a name-resolution plus TCP/443 probe of
  the four hosts this app needs. `howtherail.in` returning NXDOMAIN on a phone while Chrome loads the railway
  site is exactly the fact that distinguishes "the feed refuses us" from "one app's traffic is being filtered",
  and no CI runner can produce it.
* **Shares only how the user chooses.** `Share` (plain-text intent) and `Save to file` (system document
  picker). There is no upload path in the code, which is the only version of "log collector" compatible with a
  screen that reads "no accounts, no API keys, no ads, no analytics".
* `Clear` deletes and writes nothing afterwards, so the card goes back to "nothing collected yet" while the
  switch stays on - a user mid-repro must not have to re-enable and start the capture over.

The first line of the file is written synchronously and the rest on a daemon executor: an append per socket
attempt must never be able to slow a refresh, and an unbounded log must never be the reason a phone lags.
Rotation keeps the newer half and records the cut, because a log that eats its tail destroys the one capture
worth having.

Covered on-device by `SettingsFlowTest#diagnosticCollectorIsInertThenLiveThenEmpty` (flow `diag-log-collector`):
inert, then visibly live, then emptied by Clear. The system share sheet and document picker are deliberately
not driven - a chooser is Android's UI, and a row that fails when Google changes it tells us nothing.

### Where the network question now stands

The fix that the stale paste did not test (`258a28b`) is still the leading hypothesis: `Http` used to call
`disconnect()` in a `finally` on a process-shared instance, and Android pools keep-alive sockets per process,
so one thread tearing down a connection closes the socket another thread is handshaking on - `IOException:
closed`, uniform across unrelated hosts, sub-second, and invisible to a CI runner that issues one request at a
time. The next report either shows that fixed (attempts now serialised, sockets returned to the pool) or it
carries the class names and the DNS/VPN/Private-DNS block that says why. If the log shows `tcp443=false` for
every host while Chrome loads the same pages, the answer is a per-app network policy on the device, and this
app's honest state remains "schedule only, labelled" - not a silent fallback, and not a key baked into an APK.

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

## Resume here (state as of this push)

Branch tip `1d29bf3`, "Ask through the browser engine, because the phone says that works" — CI verdict still
pending as this is written, so treat it as unverified until the three `android` jobs say `success`. CI on the
previous tip `f00be21`: `checks ✓ debug APK ✓ tests + screens ✓` (`release` is tag-only, `skipped` is normal),
asset 10 602 337 B at 16:11:29Z = 21:41:29 IST, published to the rolling `latest-apk` release:
`https://github.com/tarungittarun/Checktrain02/releases/download/latest-apk/app-debug.apk`. **Re-check the size
and `updated_at` with `gh api` before announcing a link** — twice now a pushed commit produced no new asset
because the job that publishes it had failed.

`f00be21` is the build whose log answered the five-round question:
`21:47:15.242 [BROWSER] POST enquiry.indianrail.gov.in body=302B -> answered 159B in 1086ms`, against
`webview-get-ntes … got no document in 5000ms` and ~30 identical `IOException: closed` lines from the Java
client. Transport: settled. What is open is *protocol* — what the 159 bytes say, and which of three now-shipped
envelopes the endpoint accepts: raw JSON through the engine, the envelope as the `jsonIn` form field that the
engine's own `application/x-www-form-urlencoded` advertises, and the site visited first so NTES's cookies are
in the jar. `FeedProbe.browserWorks` switches transport — never belief — for the rest of the process as soon as
a browser variant gets any HTTP answer, which is what turns a diagnosis into a phone that shows live minutes.

Two facts to carry forward, because each cost a round trip:

1. `258a28b` never compiled - unescaped quotes inside a leaked `${'$'}`-style template - so the link silently kept
   serving `b3a0925`, and the user's "Auto off" re-test ran the old HTTP layer. Verify a paste's build line
   before interpreting it; verify the asset size before telling anyone to install.
2. The gate set is textual by design and cannot see types, operators or string contents. Three of this session's
   build breaks were invisible to all of them. What the project gained instead: `tools/ci/check_literals.py`
   (leaked escaping, doubled concat operators), `check_members` receiver checks, `companion_twins`, and the
   practice of reading the failed build's own error list from the commit comment rather than guessing.

Open question the collector exists to answer: does any request shape reach `enquiry.indianrail.gov.in` from
this phone once sockets stop being torn down? Reading the shared log answers it. If `tcp443=true` for
`one.one.one.one` and `erail.in` but every railway host hangs up, the answer is upstream policy or a per-app
network rule, and the honest product behaviour stays "labelled schedule" - not a key baked into an APK.

## The phone answered the question, and the answer was "use the browser's client"

Round four of the live-feed problem, settled by one field log instead of another theory. Reading it in order
matters, because three of my own hypotheses died in it:

| Evidence in the log | What it killed |
| --- | --- |
| `enquiry.indianrail.gov.in: 1 addr(s) A=103.110.246.84 AAAA=none` and `TCP … 443: v4=ok v6=absent in 94ms` | The address-family theory. There is no v6 to mispreference, the socket opens in 94 ms, and `post-ipv4-pinned` failing the same way was therefore expected. The pin stays as a fallback, not as the fix. |
| `api.railradar.in … v4=ok v6=ok` then `HTTP 401 in 222ms` | "The app cannot do TLS" and "the device blocks this app". Java TLS from the app, on that Wi-Fi, gets a real HTTP answer from a modern API. |
| unchanged `IOException: closed` with the socket-pool fix shipped | My keep-alive race theory. Real hygiene, not the cause. |

What survives is the shape of a client filter: **the connection opens, the TLS handshake is hung up on** -
~400 ms, eleven times, every request shape, both TLS profiles, while Chrome on the same phone in the same
minute renders the running status of the same train (`20651`, *Arrived at SAGAR JAMBAGARU at 20:55, Delay
00:22*). The remaining variable was the client, so the app now has the browser's client: `core/BrowserFetch.kt`
issues the request through the system web view - present on the device, accepted by this endpoint, no key, no
proxy of ours, nothing to sign up for. It is never attached to a window, has no `addJavascriptInterface` (so a
document cannot reach into the process, which is the one real risk of running JS at all), no file/content/
database/cache access, and reads the API document back through a JSON decode rather than a string splice. It is
abandoned and torn down if a load stalls, so one stuck page cannot poison the next refresh.

Adoption did not need to change, which is why this was affordable at all: `FeedProbe.Variant` gained one
boolean, so `webview-post` and `webview-get-ntes` are measured exactly like a header or a TLS profile, and the
first shape that produces a usable snapshot becomes that device's transport on the next refresh - live data
with no second install. The provenance line still names how the row was fetched.

Two more findings from the same file were bugs in my own instrument, both fixed: the burst of
`UnknownHostException` across five hosts within 70 ms, after all five had resolved a minute earlier, is a
filtering private-DNS profile (`privateDns=opportunistic host=adguard.com`) blinking under load - `Http` now
treats that as a blink (one 600 ms pause, one retry, message unchanged if the name is truly gone) and the
collector records the active network's nameservers, `[redacted]` when on-device, plus the network kind, which
is how "a security app sits between this app and every socket" gets distinguished from "the railway refused us"
without asking the user to read their own settings.

Standing rule re-confirmed: **CI is the only type checker here.** The `NetworkOnMainThreadException` in the
previous log, `snap.rows`, `x.size + +`, and `setFixedRouteToAddress` resolving against the JDK's copy of
`java.net.HttpURLConnection` were all invisible to eight green textual gates. Where an API exists only on the
platform, reach it the way that survives both classpaths and log why.

## Round 5, in one paragraph: the instrument was the last bug

The most useful sentence in the newest log was not about the railway: the 159-byte answer appeared on the
`[PROBE]` line as `webview-post … answered 159B, not running data` in the *copied report* and nowhere in the
*log file*, because the log line dropped `r.head`. A diagnostic that shows the payload to the person holding
the phone and hides it from the person reading the attachment loses the information exactly once, and the
round trip costs a day. Every probe line now carries the head.

Two smaller lessons from the same diff: a batch patch script that `assert`ed mid-way left one file edited and
five not, which is the desired failure mode but only if the anchors are re-read before resuming (a block I had
already rewritten twice in the session no longer matched what I expected, so a removal pair was dropped and
the additions were anchored by line number instead of by prose); and my own member checker reported a Kotlin
keyword error on the phrase "as a form field. `postUrl`" in a doc comment, which is a false positive worth
fixing in the tool rather than dodging in the prose — `strip_noise` works line-by-line and cannot see a KDoc's
interior, so block comments are masked before that rule looks at a file.
