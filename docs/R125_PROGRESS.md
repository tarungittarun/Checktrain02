# R125 · four-row Home, settings, appearance and dated-actuals investigation

Owner input: Screenshot_20261017-194416_TrackTrain.png (19:44 IST, R124).

| Request | Implementation / check | State |
| --- | --- | --- |
| Home layout | Logo+TrackTrain is a Home control; source preference moved to Settings. Station/number toggle, two stacked 75%-width station inputs and a 25%-width labelled ⇅ swap, and a date/Find row. No third Swap row. | CI/render/device verification pending. |
| Results | Full-width Edit search. Short visible heading; row-level `via erail` or `timetable`, with long source explanation retained for accessibility/diagnostics rather than a huge repeated list banner. | Pending. |
| Settings | Renamed Data source to Settings; default tracking source persists there. Light/Dark/Follow system preference applied via AppCompat DayNight and paired light/night palettes for all layouts/drawables. | Light/dark render and device tests pending. |
| Past journeys | **Do not display historical actuals from a static timetable or a page-update timestamp.** A 20-attempt, 2-train × 10-date (-1…-10) official-feed probe is included. It requires dates on actual-time cells, but even that is not independent accuracy verification. Until accurate past runs are established, the requested 10-day calendar is **not** installed; existing timetable controls are limited to yesterday/today/tomorrow. | Probe results pending; calendar intentionally deferred by the owner's condition. |
| E2E speed | Rebalance eight tracking shards to four per API/density profile, reducing 43 booted emulators to 31 **without removing methods, profiles, release smoke or coverage gates**. This reverses the prior regression where 43 rows took 16m27s against 31-row R123's 13m53s. | Measure current head against 10–12 minute target; split alone is not a measured success. |

The APK remains updateable (same package/signature; versionCode 55). No phone verification has occurred. PR #3 stays open.
