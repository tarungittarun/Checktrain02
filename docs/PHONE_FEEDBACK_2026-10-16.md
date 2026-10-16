# Phone feedback, 16 October 2026

The owner's two 09:37 screenshots (train 16221) show an inconsistent horizontal red
NTES leg versus a horizontal amber website leg, and oversized intermediate details.
The source claim belongs in the header; it must not change the route geometry.

## What the attached device diary proves

- Initial offline paint at 09:35:50 briefly showed a different MYS→MAS run of train
  16221 instead of the selected TLGP→MYS journey. Provisional device routes are now
  gated by both selected stops in the right order; the official dated route still
  replaces the provisional view when it answers.
- At 09:35:51 NTES answered the first POST with a last departure from SPV at 09:33.
  Repeated manually requested POSTs through 09:45:36 also answered, reporting
  the same stop/event. These were real network requests, not app HTTP cache hits;
  a successful reply did **not** supply a newer train event.
- The independent website GETs repeatedly showed a site-claimed SPV arrival
  09:31. At 09:49:27 a new GET instead showed a site-claimed RRB/Birur arrival
  09:46. The site's page-update label (09:48:58) is not the passage minute.
  These clocks may share upstream data; the website is undated and unverified.
- A reported departure is evidence of **the earlier stop event only**. It
  cannot establish that the train is now running, at another stop, or halted
  between two stops. The UI says motion unknown; it cannot infer it from a
  stalled NTES feed or a refreshed web page.

## Presentation and refresh policy

- One vertical line and locomotive style across sources. Height grows with
  scheduled distance between bounding halts, clamped for the phone viewport;
  expansion leaves the line height and fractional marker location unchanged.
  Untimed map points are small ticks plus a nested, height-bounded list, not
  full stop cards or fabricated arrival/departure minutes.
- The top card owns source confidence, late/early claims and checked-at
  receipts. Its source selector uses green for a live timed-stop report and amber for
  usable but unverified site/station claims; the route
  itself does not change colour with source choice. The passed-stop dots and
  at-station arrival ring now also paint identically under both sources;
  `SITE CLAIM` versus `ACTUAL ARR/DEP` text keeps the evidence distinct. A new HTTP response with the same source stop/time reads “no newer
  stop”; a changed stop/time reads “New stop report.” Site page clocks do not
  make new events. Manual and opt-in minute refresh ask the selected source
  first. After an unchanged official stop aged five minutes, the app may also
  check the website **as an unverified comparison**. It never auto-promotes
  that site's minutes into NTES actuals or moves the official marker.
- A separate newer website claim can be offered as a source-picker comparison,
  only when its past-marked stop is farther ahead on the same dated route and
  its page is fresh. This is not proof of the train's current position.

The updateable debug build uses the same stable test-only signing key. The
strict-check replacement is versionCode **40** (`1.0.39-r110`), so it installs
over both the owner's versionCode-38 phone build and the interim code-39
phone-test APK without clearing the cache or diary. It puts an unavailable
selected source's explanation immediately below the headline and returns to
the top of the card after a deliberate source change; every completed Refresh
now has a consistent checked-at receipt.

Acceptance still requires a passing current-head Android build, full E2E run,
and the owner's on-phone installation/behavior confirmation before PR #3 merges.
The five-minute full-workflow target is not claimed without a measured run.

## R111 phone diary follow-up (12:21 local diary, 16 October)

The new 12:00 screenshot is **16221**. The comparison screenshot at 12:01 is
**20689**, a different train; its blue/red indicator is a visual reference only.
We cannot use it as evidence that 16221 was between stops or halted. A red
marker in another phone app has no independently verified meaning here.

At 11:45:14 the official NTES endpoint answered a POST (79,933 bytes) with
Bageshapura departure **11:42**, delay **+18 min at that departure**. At
11:57:54 the user-triggered Refresh made another POST and got a new HTTP
answer (79,885 bytes); the decoded official last-stop event remained
Bageshapura **11:42**. The repeated 18-minute number is therefore not a
skipped network request and is not the app's HTTP cache (NTES POST replies
are uncached). A fresh *response* did not contain a fresh *stop event*.
At 11:57:59 the separate website GET timed out; `Http` fell back to a
cached body, and `PublicPageChecks` explicitly suppresses cached-body station
claims. No newer alternative could be substantiated then. At 12:08:20 a
separate fresh website GET claimed Hassan arrival **12:07** for 16221;
12:11:19's fresh GET revised the same site-claimed arrival to **12:10**.
That site's run date is inferred rather than printed, the claimed arrival
changed within minutes, and its page-update clock is not an arrival. It is
not independent verification or a replacement for NTES actuals. The app
may offer the later *site claim* for comparison if both its stop-event time
and page clock are recent; a page clock alone can no longer redate an old
claim or make it a fresh fallback. A separate verified adapter defect was
also fixed: if a newly timed halt has no delay cell, NTES/erail may not
borrow an older halt's delay and assign it to the new halt; an untimed
status-header station may not take an older event's minute. A short diary
line now records the decoded last timed stop, event minute, and delay there,
separately from the feed update clock, so a future phone report can expose
a parser/selection disagreement instead of equating HTTP length with new
train data. Refresh receipts and marker ages refer to stop events, not downloads.

R111 makes the official delay explicitly **at the last reported stop/time**,
not a current +18-minute prediction. The card starts expanded, with a
persistent More/Less handle; collapsing it keeps only delay, next stop and
source (plus its toggle). The vertical map is capped against screen height,
station row spacing scales linearly with scheduled km until screen caps, reported-stop dots share the icon's blue, full-width row bands are gone,
and disclosing map points no
longer prints the same labels both on the ruler and in the list. The train's
age is a local clock by its icon: a green check only for a <60-second-old
source event, otherwise a minute/hour label. A green check is recency, not
verified motion or GPS. No station, delay or locomotive position moves just
because the user refreshed or a minute passed. CI and on-phone validation
are required before this interpretation is considered complete; PR #3 stays
open until the owner's confirmation.

Dense-emulator E2E exposed another screen defect: the first-load panel
could stay visible after the offline timetable and headline had painted,
because the busy flag had not changed yet. Its weighted height squeezed
the route to one stop until the HTTP chain completed. The UI observer now
removes that panel immediately on the first painted state; the thin
checking bar remains visible until the network actually completes.

The old diagnostic tag `ORG source-selected=website` was printed for a
background website comparison even while NTES remained selected. This was a
log label error, not an automatic source switch; the diary now explicitly
says `probe=selected` or `probe=background comparison`. The supplied diary
only measures train 16221; other trains need their own dated on-device
source/event traces before claiming whether their upstream feeds are stale.

Fallback ranking previously compared NTES's feed-update age to the site's
stop-event age. Those are different clocks: a newly stamped response still
could carry Bageshapura's old departure. Ranking now compares stop-event
ages on both sides (only where each event time exists); it never promotes a
refreshed feed header into new train movement.

When the card is collapsed, the website's claimed delay also retains the
claimed stop minute (for example, `+25m · 12:10 site claim`); hiding the
expanded age evidence must not turn it into an apparent current delay.

The compact route's rail is now drawn continuously behind each stop item,
including the variable-km gap and the optional time sheet. Previously the
line was confined to the stop header and appeared broken between dots; the
shorter locomotive leg continues the same neutral-colour stroke.

The NTES diary's old `40 stops with reported cells` counted all timetable
rows, not the subset with timed reports. Its wording now prints both counts
separately; a long schedule never masquerades as forty observed halts.

## R112 phone follow-up (17:09 and 17:33 screenshots, 16 October)

The second comparison screenshot is of **12089**, not the tracked **20651**;
its exact scheduled minutes must not be copied into 20651's route. On the
tracked phone at 17:08–17:09, three separate website GETs returned Tiptur's
same 17:06 past-marked arrival / +18 at that stop; refreshed HTTP/page clocks
did not create a new train event. At 17:30, an independently fetched dated NTES
answer reported Arsikere arrival 17:27 / +14 and the website reported the
**same** station's arrival 17:26 / +13. This supports “the two sources agree
within 1 minute on that stop,” not proof of today's exact position or of a
website run date. R112 labels that narrow agreement explicitly where present;
otherwise it attributes a stop report to its selected source, without
repeating “unverified” over the whole screen or silently making it an official
railway actual. The source details retain the run-date and page-update limits.

The time sheet and the separately inset intermediate list in the first phone
screenshot made users tap just to see arrival/departure and disconnected the
small stations from the locomotive's route. R112 shows the scheduled and
source-reported arrival/departure pairs on each main stop before tapping, with
↓/↑ indicators and a short explanation in About times. A stop tap now expands
intermediate station dots **on the same vertical rail**, with kilometre-based
spacing and a legible floor between points. Intermediate points show only one
planned passage/arrival clock, never a departure or an “intermediate means no
stop” disclaimer. ConfirmTkt's intermediate rows have distance but no clock;
when both dated bounding halt times and distances exist, a `~`-prefixed minute
is a *timetable interpolation*, never a claimed train observation. Missing
inputs stay blank. An actual scheduled halt remains a main stop row with its
arrival/departure pair. The `Source details` sheet keeps the diagnostics behind
a brief plain-language `About times` panel.
