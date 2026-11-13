# Arrival alert: evidence and operating limits

The arrival alert is an opt-in, one-trip foreground monitor. It can notify at the selected destination, at a selected earlier stop, or when the phone is within a selected straight-line radius (2, 5, or 10 km) of the destination. Feedback is a notification, one vibration pattern, or a ringing alarm that stays active until stopped. Checks can be set to about every 1, 2, or 5 minutes; the interval is approximate because each network/location request takes time.

## What can trigger a station alert

- The monitor requests the app's route-matched running-status chain (NTES, then erail). It excludes the user endpoint override and rejects cached/schedule-only answers.
- A trigger requires an accepted actual arrival/departure field for the selected route stop, the correct ordered boarding/destination route, a recent status response, a non-future event clock, and an event no older than ten minutes. If a source-update stamp exists, it must also be non-future and no earlier than the reported event. A provider update stamp, request receipt, timetable minute, forecast, or page refresh is not a station event.
- An erail clock identical to the timetable is rejected as a possible schedule echo. NTES is accepted only after its own dated-run and actual-time validation. Neither source is independent ground truth; an alert says **source-reported, not independently verified**.
- A fresh report that has already passed the selected trigger is marked missed rather than turned into a late arrival alarm. A report already present when the user arms the alert is not replayed as a new event.
- The `whereismytrain.org.in` website, the distinct `whereismytrain.in` feed/cache, and the separate Google WIMT app are not used as station-alarm evidence.

## Phone-distance option

The distance option requests Android's coarse and precise location permissions only after the traveller selects that option, then starts a user-initiated location foreground service. Approximate location may be granted; the monitor requests one bounded phone fix per check and requires a recent fix, accuracy at or better than 250 m, a route-near location, and a train-like movement heuristic. If Android supplies only a less accurate fix, the alert waits rather than pretending the radius was reached. Distance is measured as a straight line to the selected destination's stored coordinates. This is **phone location, not train GPS**; it can be unavailable or misleading and is not proof that the user is aboard.

## Android reliability limits

An ongoing Android notification identifies an armed monitor; a check receipt is not proof of fresh train movement. The app may wait when offline, when no qualifying actual is published, or when the event/source stamp is stale. Android battery/background limits, force-stop, reboot, location-provider availability, notification permission/channel settings, Do Not Disturb, and device audio/vibration settings can delay, suppress, or stop an alert. There is no boot receiver or exact-alarm guarantee. The continuous ring is continuous only while Android keeps the foreground service running; use **Stop alarm** in the notification or app to dismiss it.

The alarm state is local to the device and excluded from backup/device transfer so a restored trip cannot silently start monitoring an old journey.
