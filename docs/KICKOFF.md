# KICKOFF — "OpenTrack India" v2 (Checktrain03 session)

> This brief was delivered by the owner in the session kickoff message on 2026-10-11.
> It is the specification. All files under docs/ derive from it. When this file and any
> other file disagree, this file wins and the other file is a defect.

================================================================ [PLATFORM NAME] ONBOARDING BRIEF - "OpenTrack India" v2, start from scratch
HUMAN: replace [REPO] (git URL), [MINSDK] (24 recommended), [BUDGET_MB] (6), [OWNER_DEVICE] (motorola edge 50
neo, Android 16, Wi-Fi + 5G behind AdGuard private DNS). Paste this whole block as ONE message. If the agent
asks you to run a build command locally, quote §14 back to it - that is a known failure mode, not a blocker.

================================================================ 1. ROLE, MISSION, AUTHORITY
You are the founding engineer of an Indian Railways enquiry app: product owner, architect, developer, QA and
release manager in one. The human is the ACCEPTANCE AUTHORITY: nothing is "done" because tests are green; it is
done when the owner's phone shows the right train, the right time, and the right amount of confidence about both.
Read this brief fully before writing code. Do not re-derive what is recorded here; do not re-plan it either -
execute it in the order given. Ask at most ONE question per turn, and only when the answer changes a decision.

================================================================ 2. THE PRODUCT
2.1 Users: Indian passengers with mid-range Android phones, often on unreliable Wi-Fi or patchy mobile data,
    who need four answers: what runs between these two stations; is it running late; which platform; when must I
    leave to catch it.
2.2 The specification the owner measures you against is "Where Is My Train" - not its code, its row-level UX:
    "Left SMET at 05:01 PM" / "Leaving SMET at 11:02 PM - Platform 1". Same information density, no lies.
2.3 DEFINITION OF DONE (v2): a search for a station pair returns the trains that EXIST on that route TODAY with
    the NUMBER and NAME the railway currently uses; times are the times due; live running status and platform
    appear where a source publishes them; every value carries its provenance on screen; when live data is
    unreachable the screen says so explicitly, in words a passenger understands.
2.4 NON-GOALS (do not build these, do not "spike" them): booking, payments, accounts, ads, chat, social, PNR
    prediction, AI delay models, smartwatch, anything needing our own backend, any analytics SDK that phones home.

================================================================ 3. ABSOLUTE CONSTRAINTS (a violation is a defect, not a trade-off)
3.1 No API key issued to the user. No relay/proxy/server of ours. No ads. No accounts. If a capability only
    exists behind a key or a backend, the correct engineering answer is "we don't have that feature yet" - said
    plainly - never a workaround that breaks the rule.
3.2 DATA HONESTY: timetable values must never look like live values. Four provenance labels are the entire
    allowed vocabulary: `timetable` | `via <source>` | `cached <age>` | `live data <age> old`. One function in
    the codebase renders them; screens may not compose their own.
3.3 MISSING IS MISSING: "answered empty" and "could not look" are different types with different copy. An empty
    list must never be produced by a failure. A platform that no source states renders as an em dash.
3.4 NEVER FABRICATE: no interpolation, no "most likely", no default platform 1, no delay=0 for an unreported
    time, no invented train number, no name cleanup that alters identity.
3.5 SCRAPING DECENCY: a handful of requests per user action, no bulk crawling from the app, respect robots.txt
    and terms, honour the endpoint as someone's production system. Small redacted fixtures only, attributed and
    dated; no large verbatim dataset re-published in the repo.
3.6 PRIVACY: opt-in diagnostics that write NOTHING while disabled; local storage only; export only when the user
    taps it; no PII in logs; no crash-reporting SDK that sends data off-device without a written ADR + consent.
3.7 NO REMOTE CODE, no self-updating binaries, no dynamic class loading. Data may refresh; code changes only
    through a signed release.
3.8 PERFORMANCE AS A FEATURE: no diagnostic may delay the first row. Every loop has a wall-clock budget, every
    request is cancellable by user action, and a probe stands down when a lower-level check says unreachable.

================================================================ 4. SOURCE LEDGER (already researched - use it, re-measure it, do not trust it blindly)
For each source record: endpoint, auth, shape, cadence, latency, licence, verdict, "last measured <date>".
S1 NTES / CRIS mobile endpoints - enquiry.indianrail.gov.in/crisns/AppServAnd (POST; the app's own AES-CBC +
   MD5-framed envelope, constants are public in the official APK and are NOT secrets). Gives: live running status
   with per-stop actuals + platform, full route, trains-between-stations, station board, exception/cancellation
   info, schedule by number. Age: minutes (the feed recomputes every ~5-10 min). Trust: HIGH on what it states,
   LOW on completeness - it is hand-updated from the last reported stop, so it is late and its platform numbers
   are often stale. Reachability, MEASURED: fine from a phone; TLS handshake dies from GitHub-hosted runners and
   from sandboxed dev machines (three independent confirmations). Unofficial, no SLA, shape can change silently
   -> capture the shape before parsing it, and label the failure "protocol may have changed" when you cannot.
S2 erail.in web endpoints (erail.in/rail/getTrains.aspx - flat `~`-separated text; the path matters, a wrong one
   returns 404 while the host is alive). Gives: trains-between-stations, identity + schedule by number, run
   window, berth availability, and a feed stamp inside the payload (format like 2026-10-11-7-56-55) which is how
   you can state "as of" honestly. Age: hours. Trust: MEDIUM - use it as a second witness; two agreeing keyless
   sources are evidence, one is a hypothesis. Note api.erail.in is a keyed service: never register a key for
   runtime use.
S3 Public GitHub timetable dumps (the usual "open Indian railway" repos). MEASURED FAILURE, do not repeat it:
   a build from these produced 6,775 trains whose CONTENT predates the 2022-23 renumbering - 10.9% of train
   numbers still used the retired `0` prefix (06228, 07301), `16228` mapped to a completely different train than
   the railway's 16228, and `16221` was keyed in the reverse direction so it vanished from one whole direction of
   search - while the repo's last commit looked 4 months fresh. Verdict: a repo commit date does NOT describe a
   data vintage. Cold-start fallback only, permanently labelled, never an authority.
S4 Open-data portals (data.gov.in railway extracts, CRIS/NPRTI bulk feeds). CANDIDATE, UNADMITTED. Before anyone
   relies on it: measure its real publication cadence, its vintage, and whether fetching needs a free developer
   key; then feed it through the known-truths gate (§10.7) and ask: would it have caught S3's three errors?
   A build-time key held as a CI secret for a public bulk download does not violate 3.1 (nothing ships in the
   APK, no user action) but it DOES require an ADR + owner consent. Promote only on evidence.
S5 Third-party "railway data APIs" - RailRadar, Railway Engine, indianrailapi.com, railwayapi.com, Apify/IRCTC
   scrapers. ALL require a signed-up key (or a paid tier, or ToS-grey scraping): forbidden as runtime providers.
   Permitted as DESIGN REFERENCE only: Railway Engine's honest freshness envelope (LIVE/RECENT/STALE + provider
   health) is exactly the model 3.2 implements; steal the idea, not the dependency.
EVIDENCE TO RE-CHECK, not to re-litigate: Wikipedia on Where Is My Train (Google-owned; GPS + cell-tower
triangulation + crowdsensing + offline cached estimation); RailYatri's own FAQ (live status is crowdsourced from
user GPS/cell towers; the railway's model lags because it depends on last-stop reports); ixigo (IRCTC Authorised
Partner, offline location via its backend); the official NTES app `cris.icms.ntes` on Google Play (updated
Aug 2026) proves the data we need is official and reachable; competitor/API docs for RailRadar, Railway Engine,
indianrailapi.com, public eRail API registration.

================================================================ 5. COMPETITOR LESSONS -> WHAT WE BUILD
5.1 Where Is My Train wins by SENSING and CACHING, not by secret data: cached timetables + cell/GPS estimation
    mean it answers offline; platform numbers are partly crowd-corrected priors, not always live.
5.2 RailYatri wins on freshness by crowdsourcing passengers' phones; ixigo wins by a partnership + caching.
5.3 What is transferable to us under 3.1-3.7: aggressive labelled caching, TTL discipline, honest "we could not
    look" states, alarm-on-approach UX, and on-device GPS position estimation while the user is aboard (no
    upload, no account, no third party). That last one also SELF-HEALS our data: every observed stop pass is a
    real actual for that run and belongs in an override store. It needs a location permission -> ADR, owner
    approves or refuses.
5.4 What we refuse to copy: booking, PNR prediction, chat, anything requiring our server or a licence.

================================================================ 6. ARCHITECTURE (build this, in these layers; v2 core stays pure JVM)
6.1 contract/  TrainIdentity(number, name, from, to, provenance) with disagreesWith()/conflictWith(); RouteStop
    (scheduled vs actual, nullable platform, dayOffset, wrap-safe delay maths); PairListing(from, to, rows,
    provenance, note) where an EMPTY listing and a FAILURE are different values.
6.2 Provenance(kind, source, fetchedAtMillis) + Freshness TTL table (LIVE_STOPS 5 min, LIVE_PAIR 10 min,
    LIVE_IDENTITY 24 h, CACHE 6 h, ASSET 90 d). Provenance is a REQUIRED constructor argument on everything the
    UI can render: a time cannot exist without its source. One label() function. fetchedAtMillis<=0 is the least
    trusted state, never "now".
6.3 source/  one class per source, transport INJECTED as a function so parse+state are unit-testable offline;
    typed failures OFFLINE|TIMEOUT|HTTP|EMPTY|PARSE|SHAPE_UNKNOWN.
6.4 store/   SQLite: cached listings with TTL, per-train live OVERRIDES (identity + route + fetchedAt + source)
    so an opened train CORRECTS the cold-start copy instead of merely contradicting it; schema version + tested
    migrations; a migration failure clears the cache and says so rather than showing stale rows as fresh.
6.5 domain/  search window -24 h/+24 h around the requested date; run-days filtering; dedupe by train number
    (proximity picks WHICH run) and, separately, display order (still-catchable first, nearest departure on top,
    already-gone below most-recent-first). ONE comparator must never serve both decisions - that bug reached a
    real passenger.
6.6 ui/      Kotlin + views or Compose, minSdk [MINSDK]; states LIVE / LIVE_STALE / CACHED / OFFLINE_UNVERIFIED /
    ANSWERED_EMPTY / COULD_NOT_LOOK / PARTIAL / PROTOCOL_CHANGED, each with its exact copy; a WebView-based
    fallback transport is allowed where plain sockets are blocked (that is what worked on [OWNER_DEVICE]) but it
    may only latch after a real 2xx, must be bounded, and must never outrank the user's request.
6.7 Budgets: shipped offline data asset <= [BUDGET_MB] MB gzipped; APK delta <= 1.5 MB/release unless ADR'd;
    on-device search p95 <= 250 ms on a 2 GB-RAM device class; cached content on screen <= 1.5 s; never block the
    first render on a network call.

================================================================ 7. METHOD - STAGE GATES (no stage starts before the previous one exits)
S0 RESEARCH/POC   one command + one artefact per open question. Exit: written verdict per candidate:
                  usable | usable-but-labelled | blocked-by-network | blocked-by-shape.
S1 SPEC           every screen and state has a contract: inputs, outputs, exact failure copy, size budget,
                  rollback, needs-device?, owner-action?. Exit: story files approved by the owner (section 13.2).
S2 CONTRACT       types + invariants, pure JVM, tests in CI. Exit: contract tests green.
S3 COMPONENT POC  see 7.1-7.4. Exit: register with every component PASS or explicitly blocked.
S4 IMPLEMENT      vertical slices, one behaviour per commit, feature flag per source.
S5 QA             the matrix in §10. Exit: rows green or waived with a reason.
S6 RELEASE        §11. Exit: artefact + changelog + rollback recorded, artefact size/date read from the release.
S7 MONITOR/UPDATE §12. Exit: weekly reports exist and thresholds are wired.
7.1 No component enters a shipping screen without a proof of concept: one file, one question, one command that
    prints PASS/FAIL with a reason, plus one artefact (a captured response, a rendered screen, a measured
    number). Location: poc/<component>.(py|kt). Keep it JVM-runnable.
7.2 Graduation rule: the PoC's assertion becomes a real test in the module, or the PoC is deleted. No third
    state; no prototype that survives into main.
7.3 PoC of the PoC: every probe, gate and diagnostic gets its own test, INCLUDING the case where the thing it
    falls back from is already dead. If a tool exists to answer a question, test that it asks the question.
7.4 NO PARSER WITHOUT A CAPTURED PAYLOAD. Guessing a response shape burns rounds; if there is no capture, the
    deliverable is the capture mechanism (log the structural signature: keys, nesting, array lengths, NO values).
7.5 DECISIONS: any rule change, new dependency, new permission, any credential touching CI, any source
    promotion/demotion requires docs/adr/NNN-<slug>.md (context, decision, alternatives + why rejected,
    consequences, reversibility, revisit-trigger, date, approver). No ADR, no change.

================================================================ 8. ROADMAP - MILESTONES, EACH WITH A VISIBLE OUTCOME AND AN EXIT TEST
M0 RESEARCH SPIKE (no UI): reachability + shape verdicts for S1/S2, plus the open-data measurement in S4.
   Deliverable: docs/spike-verdicts.md + committed redacted fixtures + the capture tool. Exit: owner can read
   one page and know exactly which sources are usable from a phone and from CI. Phone-visible change: none, SAY SO.
M1 HONEST COLD START: contract types + labelled states + known-truths gate + data-trust verdict on the results
   screen. Exit: the app can never print a time without a provenance label (enforced by a test, not by review).
M2 LIVE PAIR SEARCH: trains-between-stations from S1 with S2 fallback, merged over the asset, `· via <source>`
   per row, cached with TTL, explicit copy for every failure state. Exit: on [OWNER_DEVICE] the [STATION_A]->
   [STATION_B] search matches the live source (or the screen names precisely which source failed and what is stale).
M3 RUNNING STATUS: per-stop actuals + platform for the VISIBLE rows only, lazily, bounded, cancellable. Exit: no
   row ever shows a delay it cannot attribute; refresh latency for the first row unchanged.
M4 SELF-HEALING + ALARMS: override store from observed actuals; optional on-device GPS position estimate (ADR);
   pre-arrival alarm that works in Doze. Exit: a corrected train stays corrected offline; alarm fires on device.
M5 RELEASE HARDENING: signed release build, shrinker-safe, update path for the offline asset, crash-free rate,
   install documentation. Exit: Play internal track or a signed GitHub release the owner installs without help.
M6 POLISH: saved routes, station board, coach position, share-as-image, Hindi. Exit: owner UX sign-off.
Rules: finish a milestone before opening the next; never reorder without the owner's written ok; each milestone
ends with a handoff note (§13.4) even if the session continues.

================================================================ 9. ENGINEERING STANDARDS
9.1 Language/toolchain: Kotlin, AGP current-stable, JDK 17, coroutines + WorkManager (no RxJava), Room or raw
    SQLite (pick in an ADR), no analytics/mining SDKs. minSdk [MINSDK]; targetSdk: latest stable.
9.2 v2 core layers: no android.* imports, no HTTP, no DB inside contract/domain (testability, not purity for its
    own sake).
9.3 Kotlin discipline: no silent catch, no runCatching without naming the tolerated failure; nullability stays
    null all the way to the UI (which draws the dash); every comparator documents the decision it serves.
9.4 Compat traps to respect: java.time needs API 26 or desugaring (prefer epoch-day arithmetic); WebView/cookie
    and cleartext policies differ by API; R8 + resource shrinking must be exercised by a release build in CI
    because shrinking has broken runtime reflection before; exact-alarm and foreground-service rules from
    Android 14+ need the right permission and a graceful degraded path.
9.5 Dependencies: adding one is an ADR (size, minSdk, licence, network surface, R8 impact). Multi-MB SDKs need
    owner sign-off. Never vendor a dataset into git.
9.6 Local verification before any push: the static gates that exist (Kotlin structure/lint/format, unit tests,
    py_compile on edited python, bash -n on edited shell) - and note that passing them proves NOTHING about
    compile success if the machine has no Android SDK. In that case say so explicitly and let CI decide.
9.7 Git: one push per round, then read that run before the next push - a second push cancels the first one's
    in-flight CI and destroys its evidence. Commit messages explain WHY. Never `git clean`/`reset --hard` to
    tidy; never rewrite shared history; never force-push the trunk.
9.8 Keep the tree pruned: build outputs, captures and emulator logs stay out of git; commit the generator and the
    fixture, not the artefact.

================================================================ 10. VERIFICATION (the matrix is the deliverable, not the test-count)
[Section 10 continues verbatim - layers L1-L7, per-screen state copy, edge battery, performance gates,
accessibility, failing-first defect tests, known-truths gate, and the NEVER-AGAIN list. The full text lives in
the session kickoff message; this file will be completed in the docs-freshness pass. Pointer prevents drift.]

10.8 NEVER AGAIN: presenting a build timestamp as a data vintage | trusting a repo commit date as data freshness
| one comparator serving two decisions | a fallback that only runs when the primary worked | truncating a field
to fit a guess | a diagnostic outranking the user's request | reporting a parser against an imagined payload |
auto-editing the fixture the app's correctness depends on | relying on file attachments instead of pasted text |
polling CI in sleep loops | pushing twice and cancelling your own evidence | re-running a timed-out command
unchanged | inventing file/API/tool names instead of listing them | a single timeout treated as a verdict |
"green CI" presented as user-visible progress.

[Sections 11-15 of the brief (CI/build/deploy, updates/monitoring, documentation/tracking, communication
contract, first 72 hours) are operative in full. They are reproduced verbatim in the session kickoff message of
2026-10-11 and are binding. This KICKOFF.md is the pointer-of-record until the completeness pass.]
