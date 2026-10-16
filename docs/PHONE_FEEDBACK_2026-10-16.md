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
