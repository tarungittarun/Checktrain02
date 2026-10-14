# 16227 · 15 Oct 2026 · actual-position-only investigation

The owner's 03:07–03:10 IST phone diary for the **14 Oct origin run** is the primary evidence. NTES returned 29 scheduled halts and reported minutes twice in under a second; after a 6.5-second timeout the screen wrongly reverted to the bundled 11-halt SBC→SMET route instead of the dated 29-halt MYS→TLGP route. The next NTES request succeeded in 815 ms and reported an 80-minute delay at the last halt, Arsikere. **It did not report a moving GPS coordinate between Arsikere and Birur.** A time/distance interpolation between those stops is not an actual location at 03:10.

The two fallback failures had different causes: `OfficialRunCache.decode` disallowed real one-character station code `Y`, discarding the saved 29-halt official run; `RailRepository.live` preferred the bundled 11-halt timetable to the already-validated, saved 29-halt schedule. Both are corrected with a one-character-code regression test. On a timeout, the full dated schedule remains if its run/span checks pass; a saved official report is explicitly *last-known*, never current.

The tracking screen now prints the **last reported halt/event** and states that the position since that event is unknown. Its progress is pinned to that halt's *scheduled km*, not a fabricated moving km. The proportional interpolated train icon and future delay-propagated times are hidden in the position-only screen; future rows stay scheduled. Historical reported stop times remain labelled `Act` (they are historical observations, not remaining-leg forecasts). The next-stop heading gives a scheduled time, not an ETA. No page result, static kilometer point, or projected minute becomes a moving-position claim.

## Five public candidate lanes tested/investigated, **not** five verified position feeds

The bounded GitHub Actions source trial interrogates runningstatus.in, ConfirmTkt, eRail, RailYatri and the **WIMT web cache** (not the WIMT phone app). Its findings are posted to the trial commit. Direct official NTES is the separate baseline proven on the owner's phone. The first three trial URLs, time limits and observation guard live in `tools/ci/probe_position_feeds.py`; the other two have their own scripts. An HTTP 200 or a matching date *mentioned* somewhere on the page is not enough: a candidate must match the **origin run**, expose a past observation/event timestamp, clear freshness checks and avoid schedule/ETA echoes before it may locate the train. A website wrapping NTES is not independent NTES corroboration. Do not count a source as usable actual position simply because it has a name or answers fast.

| Lane | Last on-device same-run evidence before this trial | Permitted effect now |
| --- | --- | --- |
| Direct NTES | 29 halts, reported stop events; timed successes at 03:07, 03:09, 03:10; timeout at 03:10:29 | Last reported halt/event, historical Act rows; not GPS |
| runningstatus.in | 403 or 26 rows without proved run (`run=null`); zero validated same-run events | Manual probe; no position without dated verified event |
| ConfirmTkt | Page day 15 Oct did not establish 14 Oct origin run | Static intermediate coverage; no position from page forecasts |
| eRail | Small replies / running-status help page, no verified minutes | Identity/static route when validated; no live position from identity |
| RailYatri | API names upstream `mntes` and pages project future departures as `Departed` | Research only; not an independent position source |
| WIMT web cache | 13 candidate stop notes; ETA and pre-update/future fields rejected; disagreed with WIMT phone screenshots | Candidate annotations only, neither Act nor GPS nor position |

The trial may expose a useful new schema; no new position provider is promoted without recording and testing concrete same-run past-event evidence. Third-party undocumented endpoints or APIs requiring keys/permission cannot be treated as reliable authorized integration by relabeling them. **At present five verified independently usable live-position sources are not established.**
