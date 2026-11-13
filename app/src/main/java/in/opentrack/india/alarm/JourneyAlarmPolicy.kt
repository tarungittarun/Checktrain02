package `in`.opentrack.india.alarm

import `in`.opentrack.india.core.Geo
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.PositionEngine
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainRoute
import `in`.opentrack.india.ui.SourceFreshness
import java.util.Locale

/** Arrival triggers deliberately accept evidence, not a screen refresh or a timetable prediction. */
object JourneyAlarmPolicy {
    sealed class Decision {
        data class Waiting(val detail: String) : Decision()
        data class Fire(val title: String, val detail: String, val evidenceAtMillis: Long) : Decision()
        data class Missed(val detail: String, val evidenceAtMillis: Long) : Decision()
    }

    data class Baseline(val seq: Int = 0, val eventMinute: Int? = null)

    data class PhoneFix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val atMillis: Long,
        val speedKmh: Float,
        val movedMeters: Float,
        val matchesTrainRoute: Boolean,
    )

    fun baseline(config: JourneyAlarmConfig, route: TrainRoute,
                 answer: PositionEngine.Answer,
                 nowMillis: Long = System.currentTimeMillis()): Baseline {
        if (!routeMatches(config, route) || answer.unresolved || answer.held ||
            answer.position.provenance !in LIVE_EVENT_SOURCES ||
            answer.position.ageSeconds > PositionEngine.STALE_MS / 1_000L ||
            answer.position.feedAgeSeconds?.let {
                it > SourceFreshness.STALE_AFTER_MS / 1_000L
            } == true || answer.position.feedStampMillis?.let { it > nowMillis } == true)
            return Baseline()
        val code = answer.anchorStopCode ?: return Baseline()
        val stop = route.stops.firstOrNull { it.code.equals(code, true) } ?: return Baseline()
        val fields = fieldsAt(answer, stop.code) ?: return Baseline(stop.seq)
        val minute = fields.actualDepartureMin ?: fields.actualArrivalMin
        val stamp = answer.position.feedStampMillis
        if (minute != null && stamp != null && stamp <
            RailTime.railMinuteToEpochMillis(config.originEpochDay, minute)) return Baseline()
        val createdMinute = ((RailTime.istEpochDay(config.createdAtMillis) - config.originEpochDay) *
            RailTime.MINUTES_PER_DAY + RailTime.istMinuteOfDay(config.createdAtMillis)).toInt()
        // If the trigger stop's event happened after the user pressed Set, let the first
        // monitor pass deliver it. An event in the same minute is ambiguous and is not enough.
        if (stop.seq == config.triggerSeq && minute != null && minute > createdMinute)
            return Baseline((stop.seq - 1).coerceAtLeast(0))
        return Baseline(stop.seq, minute)
    }

    fun stationEvent(config: JourneyAlarmConfig, route: TrainRoute,
                     answer: PositionEngine.Answer, nowMillis: Long): Decision {
        if (config.trigger == AlertTrigger.PHONE_DISTANCE)
            return Decision.Waiting("Waiting for phone location near ${config.toName}")
        if (!routeMatches(config, route))
            return Decision.Waiting("The available route does not match this selected journey")
        val position = answer.position
        if (position.provenance !in LIVE_EVENT_SOURCES || answer.unresolved || answer.held)
            return Decision.Waiting("Waiting for a fresh route-matched running-status event")
        if (position.ageSeconds > PositionEngine.STALE_MS / 1_000L)
            return Decision.Waiting("The running-status reply is old; waiting for a newer one")
        if (position.feedAgeSeconds?.let { it > SourceFreshness.STALE_AFTER_MS / 1_000L } == true)
            return Decision.Waiting("The source update is old; waiting for a newer running-status reply")
        val feedStamp = position.feedStampMillis
        if (feedStamp != null && feedStamp > nowMillis)
            return Decision.Waiting("The source update time is in the future; waiting for a valid reply")

        val anchorCode = answer.anchorStopCode?.uppercase()
            ?: return Decision.Waiting("The latest running-status reply names no route station")
        val anchor = route.stops.firstOrNull { it.code.equals(anchorCode, true) }
            ?: return Decision.Waiting("The latest reported station is not on this selected route")
        val anchorFields = fieldsAt(answer, anchor.code)
        val anchorMinute = anchorFields?.let { it.actualDepartureMin ?: it.actualArrivalMin }
        val anchorAt = anchorMinute?.let { RailTime.railMinuteToEpochMillis(config.originEpochDay, it) }
        val anchorAge = SourceFreshness.ageMillis(anchorAt, nowMillis)
        val anchorEventFresh = anchorAge != null && anchorAge <= PositionEngine.STALE_MS
        if (anchorMinute != null && anchorEventFresh && anchor.seq > config.toSeq)
            return Decision.Missed(
                "A fresh source report is already past ${config.toName}; no arrival alarm was sent.",
                anchorAt!!,
            )

        val target = route.stops.firstOrNull { it.code.equals(config.triggerCode, true) }
            ?: return Decision.Waiting("The selected trigger station is not on this route")
        if (target.seq != config.triggerSeq || target.seq > config.toSeq)
            return Decision.Waiting("The selected trigger station no longer matches this route")
        val fields = fieldsAt(answer, target.code)
            ?: return waitOrMiss(anchor, anchorAt, anchorEventFresh, config,
                "No actual arrival or departure time at ${target.name}")
        val minute = fields.actualDepartureMin ?: fields.actualArrivalMin
            ?: return waitOrMiss(anchor, anchorAt, anchorEventFresh, config,
                "No actual arrival or departure time at ${target.name}")
        val scheduled = if (fields.actualDepartureMin != null) target.departureMin
            else target.arrivalMin
        if (position.provenance == SourceKind.PROVIDER && scheduled != null && minute == scheduled)
            return waitOrMiss(anchor, anchorAt, anchorEventFresh, config,
                "The erail time at ${target.name} matches the timetable; it may be a schedule echo")

        val eventAt = RailTime.railMinuteToEpochMillis(config.originEpochDay, minute)
        val age = SourceFreshness.ageMillis(eventAt, nowMillis)
            ?: return waitOrMiss(anchor, anchorAt, anchorEventFresh, config,
                "The reported station event time is unknown or in the future")
        if (age > PositionEngine.STALE_MS)
            return waitOrMiss(anchor, anchorAt, anchorEventFresh, config,
                "The last event at ${target.name} is ${SourceFreshness.label(eventAt, nowMillis)}")
        if (feedStamp != null && feedStamp < eventAt)
            return Decision.Waiting("The source update stamp predates the reported station event; waiting for a consistent reply")
        val createdMinute = ((RailTime.istEpochDay(config.createdAtMillis) - config.originEpochDay) *
            RailTime.MINUTES_PER_DAY + RailTime.istMinuteOfDay(config.createdAtMillis)).toInt()
        if (minute <= createdMinute)
            return waitOrMiss(anchor, anchorAt, anchorEventFresh, config,
                "This station event was reported at or before the alert was set")
        if (target.seq < config.baselineSeq ||
            (target.seq == config.baselineSeq &&
                config.baselineEventMinute?.let { minute <= it } == true))
            return waitOrMiss(anchor, anchorAt, anchorEventFresh, config,
                "Waiting for a new station event after the alert was set")

        val event = if (fields.actualDepartureMin != null) "departure" else "arrival"
        val source = sourceLabel(position.provenance)
        val evidence = "$source reports $event at ${target.name} " +
            "${RailTime.formatRailMinute(minute)} · ${SourceFreshness.label(eventAt, nowMillis)}"
        if (target.seq == config.triggerSeq) return Decision.Fire(
            title = if (config.trigger == AlertTrigger.PREVIOUS_STOP_REPORT)
                "${target.name} reported · before ${config.toName}"
            else "${config.toName} reported",
            detail = "$evidence · source-reported, not independently verified",
            evidenceAtMillis = eventAt,
        )
        return Decision.Waiting("Last event: $evidence · waiting for ${config.triggerName}")
    }

    fun distanceEvent(config: JourneyAlarmConfig, route: TrainRoute, fix: PhoneFix?,
                      nowMillis: Long): Decision {
        if (config.trigger != AlertTrigger.PHONE_DISTANCE)
            return Decision.Waiting("Waiting for a fresh running-status station event")
        if (!routeMatches(config, route))
            return Decision.Waiting("The available route does not match this selected journey")
        val location = fix ?: return Decision.Waiting("No recent phone location; distance alert is waiting")
        val age = nowMillis - location.atMillis
        if (age !in 0..90_000L)
            return Decision.Waiting("Phone location is old; distance alert is waiting for a new fix")
        if (!location.accuracyMeters.isFinite() || location.accuracyMeters !in 0f..250f)
            return Decision.Waiting("Phone location accuracy is too low for a distance alert")
        if (!location.matchesTrainRoute)
            return Decision.Waiting("Phone location does not match a station on this train's route")
        val moving = location.speedKmh in 8f..160f || location.movedMeters > 250f
        if (!moving)
            return Decision.Waiting("Waiting for phone movement along the route before using distance")
        val target = route.stops.firstOrNull { it.code.equals(config.toCode, true) }
            ?: return Decision.Waiting("Destination coordinates are unavailable on this route")
        val lat = target.lat ?: return Decision.Waiting("Destination coordinates are unavailable")
        val lon = target.lon ?: return Decision.Waiting("Destination coordinates are unavailable")
        val km = Geo.kmBetween(location.latitude, location.longitude, lat, lon)
        if (!km.isFinite()) return Decision.Waiting("Could not measure distance to ${config.toName}")
        if (km > config.distanceKm) {
            val rounded = String.format(Locale.US, "%.1f", km)
            return Decision.Waiting("Phone location is $rounded km straight-line from ${config.toName}")
        }
        val rounded = String.format(Locale.US, "%.1f", km)
        return Decision.Fire(
            title = "Near ${config.toName}",
            detail = "Phone location is $rounded km straight-line from ${config.toName} " +
                "(accuracy ±${location.accuracyMeters.toInt()} m); this is not train GPS",
            evidenceAtMillis = location.atMillis,
        )
    }

    fun sourceLabel(source: SourceKind): String = when (source) {
        SourceKind.PROVIDER_NTES -> "NTES running status"
        SourceKind.PROVIDER -> "erail running-status feed"
        else -> source.label
    }

    private fun routeMatches(config: JourneyAlarmConfig, route: TrainRoute): Boolean {
        if (!route.trainNo.equals(config.trainNo, true)) return false
        val from = route.stops.firstOrNull { it.code.equals(config.fromCode, true) } ?: return false
        val to = route.stops.firstOrNull { it.code.equals(config.toCode, true) } ?: return false
        val trigger = route.stops.firstOrNull { it.code.equals(config.triggerCode, true) } ?: return false
        return from.seq < to.seq && to.seq == config.toSeq &&
            trigger.seq == config.triggerSeq && trigger.seq > from.seq && trigger.seq <= to.seq
    }

    private fun fieldsAt(answer: PositionEngine.Answer, code: String) =
        answer.perStop[code] ?: answer.perStop.entries.firstOrNull {
            it.key.equals(code, true)
        }?.value

    private fun waitOrMiss(anchor: `in`.opentrack.india.model.StopRow, anchorAt: Long?,
                           anchorEventFresh: Boolean, config: JourneyAlarmConfig,
                           reason: String): Decision =
        if (anchor.seq > config.triggerSeq && anchorEventFresh && anchorAt != null)
            Decision.Missed(
                "A later station has been reported, but no qualifying event was reported at " +
                    "${config.triggerName}. No alarm was sent. $reason", anchorAt)
        else Decision.Waiting(reason)

    private val LIVE_EVENT_SOURCES = setOf(SourceKind.PROVIDER_NTES, SourceKind.PROVIDER)
}
