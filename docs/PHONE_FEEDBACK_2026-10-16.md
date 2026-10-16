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

The updateable debug build uses the same stable test-only signing key and bumps
versionCode to **39** (`1.0.38-r109`) so Android can install it over the owner's
previous versionCode-38 phone build without clearing the cache or diary.

Acceptance still requires a passing current-head Android build, full E2E run,
and the owner's on-phone installation/behavior confirmation before PR #3 merges.
The five-minute full-workflow target is not claimed without a measured run.
