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
    /** Alert evidence is deliberately narrower than the app's tracking-source list. */
    enum class AlertEvidenceSource(val displayName: String) {
        NTES("NTES running status"),
        WIMT("WIMT .in live-status feed"),
    }

    sealed class Decision {
        data class Waiting(val detail: String) : Decision()
        data class Fire(val title: String, val detail: String, val evidenceAtMillis: Long) : Decision()
        data class Missed(val detail: String, val evidenceAtMillis: Long) : Decision()
    }

    data class Baseline(val seq: Int = 0, val eventMinute: Int? = null)

    /** A later-stop result is terminal only after every available source has been checked. */
    data class SourceDecision(val sourceLabel: String, val decision: Decision)

    /**
     * Return the first source that can end an alert. A qualifying Fire wins immediately in
     * source order; a single source's Missed verdict is held until all sources were reachable,
     * so a second source can still report the selected station event.
     */
    fun firstTerminalSource(results: List<SourceDecision>, allSourcesChecked: Boolean): SourceDecision? =
        results.firstOrNull { it.decision is Decision.Fire }
            ?: if (allSourcesChecked) results.firstOrNull {
                it.decision is Decision.Missed
            } else null

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
                 nowMillis: Long = System.currentTimeMillis(),
                 source: AlertEvidenceSource = AlertEvidenceSource.NTES): Baseline {
        if (!routeMatches(config, route) || answer.unresolved || answer.held ||
            !sourceMatches(source, answer.position.provenance) ||
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

    sealed class SetupEligibility {
        data class Upcoming(
            val baseline: Baseline,
            val currentStation: String,
            val source: AlertEvidenceSource,
        ) : SetupEligibility()
        data class Reached(
            val station: String,
            val evidenceAtMillis: Long,
            val source: AlertEvidenceSource,
        ) : SetupEligibility()
        data class Unavailable(
            val detail: String,
            val source: AlertEvidenceSource? = null,
        ) : SetupEligibility()
    }

    /**
     * A station is selectable only when a direct, fresh NTES or WIMT report places the train
     * strictly before it on the exact selected route. Timetable time is intentionally absent
     * from this decision: a delayed train can still be approaching a stop whose schedule passed.
     */
    fun setupEligibility(
        config: JourneyAlarmConfig,
        route: TrainRoute,
        answer: PositionEngine.Answer,
        nowMillis: Long,
        source: AlertEvidenceSource,
    ): SetupEligibility {
        if (!routeMatches(config, route))
            return SetupEligibility.Unavailable("The live report does not match the selected train and ordered journey.")
        val position = answer.position
        if (!sourceMatches(source, position.provenance))
            return SetupEligibility.Unavailable("Only direct NTES or WIMT running-status reports can verify the current station.")
        if (answer.unresolved || answer.held)
            return SetupEligibility.Unavailable("The live report cannot be placed reliably on this route.")
        if (position.ageSeconds !in 0..(PositionEngine.STALE_MS / 1_000L))
            return SetupEligibility.Unavailable("The running-status reply is stale; refresh and try again.")
        if (position.feedAgeSeconds?.let { it !in 0..(PositionEngine.STALE_MS / 1_000L) } == true)
            return SetupEligibility.Unavailable("The source update is stale; refresh and try again.")
        val feedStamp = position.feedStampMillis
        if (source == AlertEvidenceSource.WIMT && feedStamp == null)
            return SetupEligibility.Unavailable("WIMT did not provide a source-update time for this run.")
        if (feedStamp != null && (feedStamp > nowMillis || nowMillis - feedStamp > PositionEngine.STALE_MS))
            return SetupEligibility.Unavailable("The source update is stale or in the future.")

        val anchorCode = answer.anchorStopCode
            ?: return SetupEligibility.Unavailable("The live report names no current route station.")
        val anchors = route.stops.filter { it.code.equals(anchorCode, true) }
        if (anchors.size != 1)
            return SetupEligibility.Unavailable("The reported station is missing or ambiguous on this route.")
        val anchor = anchors.first()
        if (position.anchorSeq != anchor.seq)
            return SetupEligibility.Unavailable("The reported station sequence conflicts with the selected route.")
        val anchorFields = fieldsAt(answer, anchor.code)
            ?: return SetupEligibility.Unavailable("The live report has no observed time at ${anchor.name}.")
        val anchorMinute = anchorFields.actualDepartureMin ?: anchorFields.actualArrivalMin
            ?: return SetupEligibility.Unavailable("The live report has no observed time at ${anchor.name}.")
        val anchorAt = RailTime.railMinuteToEpochMillis(config.originEpochDay, anchorMinute)
        val anchorAge = SourceFreshness.ageMillis(anchorAt, nowMillis)
            ?: return SetupEligibility.Unavailable("The reported station-event time is unknown or in the future.")
        if (anchorAge > PositionEngine.STALE_MS)
            return SetupEligibility.Unavailable("The last observed station event is stale; refresh and try again.")
        if (feedStamp != null && feedStamp < anchorAt)
            return SetupEligibility.Unavailable("The source update predates the reported station event.")

        val targetCode = if (config.trigger == AlertTrigger.PHONE_DISTANCE) config.toCode
            else config.triggerCode
        val targetSeq = if (config.trigger == AlertTrigger.PHONE_DISTANCE) config.toSeq
            else config.triggerSeq
        val target = route.stops.firstOrNull { it.code.equals(targetCode, true) }
            ?.takeIf { it.seq == targetSeq }
            ?: return SetupEligibility.Unavailable("The selected stop no longer matches this route.")
        if (route.stops.count { it.code.equals(target.code, true) } > 1)
            return SetupEligibility.Unavailable("${target.name} appears more than once on this route; the live report cannot identify this visit safely.")

        // A fresh source can contain a target-station event even if its headline anchor is
        // inconsistent. Never allow that contradiction to arm a duplicate or already-reached alert.
        val targetFields = fieldsAt(answer, target.code)
        val targetMinute = targetFields?.let { it.actualDepartureMin ?: it.actualArrivalMin }
        if (targetMinute != null) {
            val targetAt = RailTime.railMinuteToEpochMillis(config.originEpochDay, targetMinute)
            if (targetAt <= nowMillis) {
                val targetAge = SourceFreshness.ageMillis(targetAt, nowMillis)
                if (targetAge != null && targetAge <= PositionEngine.STALE_MS &&
                    (feedStamp == null || feedStamp >= targetAt))
                    return SetupEligibility.Reached(target.name, targetAt, source)
                return SetupEligibility.Unavailable("The source report contains an old or inconsistent event at ${target.name}; refresh before setting this alert.")
            }
        }
        if (anchor.seq >= target.seq)
            return SetupEligibility.Reached(anchor.name, anchorAt, source)

        return SetupEligibility.Upcoming(
            baseline(config, route, answer, nowMillis, source),
            currentStation = anchor.name,
            source = source,
        )
    }

    fun stationEvent(config: JourneyAlarmConfig, route: TrainRoute,
                     answer: PositionEngine.Answer, nowMillis: Long,
                     sourceDisplayName: String? = null,
                     source: AlertEvidenceSource = AlertEvidenceSource.NTES): Decision {
        if (config.trigger == AlertTrigger.PHONE_DISTANCE)
            return Decision.Waiting("Waiting for phone location near ${config.toName}")
        if (!routeMatches(config, route))
            return Decision.Waiting("The available route does not match this selected journey")
        val position = answer.position
        if (!sourceMatches(source, position.provenance) || answer.unresolved || answer.held)
            return Decision.Waiting("Waiting for a fresh route-matched NTES or WIMT running-status event")
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

        // Inspect the selected stop's own reported event before concluding that the train has
        // passed the destination. A source may report the target's actual time while its current
        // anchor is already a later stop; that target event must still produce the final alert.
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
        // A schedule-matching cell from the generic provider is not a confirmed event. WIMT
        // may use it only after its direct probe has matched run date, endpoints and fresh stamp.
        if (source != AlertEvidenceSource.WIMT && position.provenance == SourceKind.PROVIDER &&
            scheduled != null && minute == scheduled)
            return waitOrMiss(anchor, anchorAt, anchorEventFresh, config,
                "The provider time at ${target.name} matches the timetable; it may be a schedule echo")

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
        val source = sourceDisplayName?.takeIf { it.isNotBlank() }
            ?: sourceLabel(position.provenance)
        val evidence = "$source reports $event at ${target.name} "
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

    /**
     * A check is successful only when at least one independent live source returned a fresh,
     * route-qualified actual-event report. A partial source outage is not an outage for the
     * traveller if another source supplied usable data.
     */
    fun hasUsableSourceData(config: JourneyAlarmConfig, route: TrainRoute,
                            answer: PositionEngine.Answer, nowMillis: Long,
                            source: AlertEvidenceSource = AlertEvidenceSource.NTES): Boolean {
        if (config.trigger == AlertTrigger.PHONE_DISTANCE || !routeMatches(config, route)) return false
        val position = answer.position
        if (!sourceMatches(source, position.provenance) || answer.unresolved || answer.held ||
            position.ageSeconds > PositionEngine.STALE_MS / 1_000L ||
            position.feedAgeSeconds?.let { it > SourceFreshness.STALE_AFTER_MS / 1_000L } == true ||
            position.feedStampMillis?.let { it > nowMillis } == true) return false
        val anchorCode = answer.anchorStopCode ?: return false
        val anchor = route.stops.firstOrNull { it.code.equals(anchorCode, true) } ?: return false
        val fields = fieldsAt(answer, anchor.code) ?: return false
        val minute = fields.actualDepartureMin ?: fields.actualArrivalMin ?: return false
        val scheduled = if (fields.actualDepartureMin != null) anchor.departureMin else anchor.arrivalMin
        // Schedule-matching clocks count only from the strictly qualified, direct WIMT source.
        if (source != AlertEvidenceSource.WIMT && position.provenance == SourceKind.PROVIDER &&
            scheduled != null && minute == scheduled) return false
        val eventAt = RailTime.railMinuteToEpochMillis(config.originEpochDay, minute)
        val age = SourceFreshness.ageMillis(eventAt, nowMillis) ?: return false
        if (age > PositionEngine.STALE_MS) return false
        if (position.feedStampMillis?.let { it < eventAt } == true) return false
        return true
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
        // SourceKind.PROVIDER is deliberately shared by WIMT and other adapters; only the
        // direct alert probe carries enough provenance to name WIMT. Never infer eRail here.
        SourceKind.PROVIDER -> "Unidentified live provider"
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

    private fun sourceMatches(source: AlertEvidenceSource, provenance: SourceKind): Boolean =
        when (source) {
            AlertEvidenceSource.NTES -> provenance == SourceKind.PROVIDER_NTES
            AlertEvidenceSource.WIMT -> provenance == SourceKind.PROVIDER
        }
}
