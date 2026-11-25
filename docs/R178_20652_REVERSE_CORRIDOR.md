# R178 — 20652 reverse corridor for missing pass-through stations

## Reproduction and evidence

The 25 Nov 2026 20652 diary records repeated taps on **DRU→ASK** with
`points=0`, `map=unavailable`, and `static=none` (09:49:58–09:51:06).
The attempted RailGeo route request returned a 537-byte maintenance page, not a
route. The bundled historical corridor asset already contains the same physical
TLGP–SBC section in the opposite direction, including **Ballekere Halt (BLKR),
Devanur (VNR), and Banavar (BVR)** between Kadur (DRU) and Arsikere (ASK).
It also omits at least one current scheduled halt, KMSI, so accepting the whole
old route as today's route would be unsafe.

## Change

`OfflineCorridorMap.parse` now recognizes only the exact train/endpoints
**20652 TLGP→SBC** as eligible to reuse the reversed **SBC→TLGP slice** of that
archived corridor. It checks current scheduled halt order, then validates each
consecutive timetable leg independently for map order, missing/skipped scheduled
halts, credible distance span and map-to-timetable distance ratio. Valid
intermediate distances are scaled onto that leg's dated timetable axis. A leg
whose endpoints are absent or whose checks fail stays unmapped; this is why
**ARU→KMSI and KMSI→SMET remain unmapped** with this archive. No old timetable
clock, station event, halt flag or live position is copied into the app.

The existing UI disclosure remains user-controlled: tap **Stations** on a
mapped leg to see the pass-through names. These points are explicitly labelled
as coming from an old static corridor and remain without passage times. They do
not alter NTES, WIMT, the marker, or the current-position claim. This change
addresses the diary's missing DRU→ASK map; it does **not** decide whether the
website, WIMT `.in` cache or NTES has the correct event clocks. The attached
screenshot's DRU departure (09:01) still differs from both diary feeds (08:59),
and no source in that diary corroborates the screenshot's later ASK clocks.

## Regression coverage and limits

- `OfflineCorridorMapTest` verifies the reversed 20652 route, the precise
  BLKR→VNR→BVR order, null event clocks, and refusal to mark the two KMSI-adjacent
  legs mapped.
- `InlineRouteRenderTest` expands the actual DRU→ASK row and checks its rendered
  station disclosure/accessibility text and absence of invented passage times.
- Local static source checks and `git diff --check` pass. Gradle/JVM/render tests
  could not be run in this workspace because no Java executable or `JAVA_HOME`
  is installed; a green Android Actions run for this change is still required.

No new website or railway request was made. PR #3 remains open and unmerged.
