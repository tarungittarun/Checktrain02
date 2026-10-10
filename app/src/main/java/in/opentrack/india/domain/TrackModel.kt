package `in`.opentrack.india.domain

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import kotlin.math.abs

/**
 * Everything the tracking screen prints, computed once, in one place.
 *
 * This type exists because of a specific bug. The header was phrased by the position engine while the
 * stop list decided each row's state with its own `seq < anchorSeq` comparison, and the two disagreed:
 * the screen said "departed TIPTUR" and showed Tiptur's own row as *not* departed. Two rules over the
 * same facts will drift like that, and the user reads whichever they look at first. So the header is
 * now derived from the rows below, and the rows are the only place a state is computed.
 *
 * Pure Kotlin, no Android: the invariant that matters ("the highlighted row is the row the headline
 * talks about") is asserted in a plain unit test.
 */
object TrackModel {

    enum class State { PASSED, HERE, NEXT, LATER, CANCELLED, SKIPPED }

    enum class Tone { LIVE_OK, LIVE_LATE, LIVE_EARLY, SCHEDULE, PROBLEM, NONE }

    /**
     * One stop as the screen shows it.
     *
     * Times are rail minutes (see [RailTime]); the formatter adds the `( +1d )` the traveller needs to
     * read a 01:10 arrival on a train that started yesterday.
     *
     * @param estimated the shown time is schedule plus a carried delay, not a report about the train.
     *   The UI must style it as a forecast: a projected minute and a confirmed minute are different
     *   claims and a passenger decides differently on each.
     * @param isAnchor the stop the headline is about. Highlighting this row is what keeps the sentence
     *   and the table pointing at the same place.
     */
    data class Stop(
        val seq: Int,
        val code: String,
        val name: String,
        val schedArr: Int?,
        val schedDep: Int?,
        val showArr: Int?,
        val showDep: Int?,
        val delay: Int?,
        val estimated: Boolean,
        val platform: String?,
        val state: State,
        val isAnchor: Boolean,
        val kmFromOrigin: Int,
        val kmFromBoard: Int,
        val kmRemaining: Int,
        val isBoarding: Boolean,
        val isAlighting: Boolean,
        /** minutes from `now` to this stop's shown departure (negative = it has gone) */
        val minutesFromNow: Int?,
    ) {
        /** The minute the comparison for this row is made on: departure, or arrival at the end. */
        val pivotMinute: Int? get() = showDep ?: showArr
    }

    /** Screen-ready strings, so the Activity contains no arithmetic and cannot invent a claim. */
    data class View(
        val trainNo: String,
        val trainName: String,
        val routeLabel: String,
        val runDateLabel: String,
        val headline: String,
        val tone: Tone,
        val provenanceLabel: String,
        val isLive: Boolean,
        val ageLabel: String,
        val delayLabel: String,
        val nextLabel: String,
        val progressLabel: String,
        val progressPct: Int,
        /**
         * Where the timetable puts the train at this minute, as a percentage of the same segment. The
         * progress bar draws it as a tick beside the fill, so "how late" is visible as a distance
         * instead of only as a number; -1 when there is nothing to compare against.
         */
        val schedulePct: Int,
        val rows: List<Stop>,
        val hasDistances: Boolean,
        val note: String?,
        val anchorSeq: Int,
    )

    /** Number of rows the timeline shows as "the train is here or past here". */
    fun passedCount(rows: List<Stop>): Int = rows.count { it.state == State.PASSED || it.isAnchor }

    /**
     * @param boardSeq/alightSeq the traveller's own segment. Distance and progress are measured from
     *   the boarding stop, not from the train's first station: "138 km covered" means nothing if the
     *   passenger got on at stop 40 of 60, which is the mistake most trackers make.
     * @param perStop what a feed confirmed per stop; may be empty.
     */
    fun build(
        route: TrainRoute,
        pos: TrainPosition,
        perStop: Map<String, LiveStopFields>,
        nowRailMinute: Int,
        boardSeq: Int,
        alightSeq: Int,
        runEpochDay: Long,
        note: String?,
    ): View {
        val live = pos.provenance.live
        val board = route.stops.firstOrNull { it.seq == boardSeq }
        val boardKm = board?.schedKm ?: 0
        val hasAnchor = pos.anchorSeq > 0
        val overlay = PositionEngine.delayProfile(route, perStop, pos.anchorSeq, pos.delayMinutes)
        // The first stop still ahead of the clock, found once. Inside the row loop this would be a
        // second scan per row, and a 950-halt passenger train is 900 000 comparisons on the UI thread.
        val nextIdx = route.stops.indexOfFirst { (it.departureMin ?: it.arrivalMin ?: 0) > nowRailMinute }

        val stops = route.stops.mapIndexed { index, s ->
            val liveFields = perStop[s.code]
            val carried = overlay.getOrNull(index)
            val actualArr = liveFields?.actualArrivalMin
            val actualDep = liveFields?.actualDepartureMin
            val delay = liveFields?.let { it.delayArrivalMin ?: it.delayDepartureMin } ?: carried
            // Confirmed time where the feed reported one; otherwise the schedule, nudged by the delay
            // we are allowed to carry forward (which delayProfile restricts to stops still ahead).
            val estArr = s.arrivalMin?.let { it + (delay ?: 0) }
            val estDep = s.departureMin?.let { it + (delay ?: 0) }
            val shown = actualDep != null || actualArr != null
            val state = state(s, index, nextIdx, pos, live, nowRailMinute)
            Stop(
                seq = s.seq, code = s.code, name = s.name,
                schedArr = s.arrivalMin, schedDep = s.departureMin,
                showArr = actualArr ?: estArr, showDep = actualDep ?: estDep,
                delay = delay, estimated = !shown,
                platform = liveFields?.platform,
                state = state,
                isAnchor = hasAnchor && s.seq == pos.anchorSeq,
                kmFromOrigin = s.schedKm,
                kmFromBoard = (s.schedKm - boardKm).coerceAtLeast(0),
                kmRemaining = (alightKm(route, alightSeq) - s.schedKm).coerceAtLeast(0),
                isBoarding = s.seq == boardSeq,
                isAlighting = s.seq == alightSeq,
                minutesFromNow = (s.departureMin ?: s.arrivalMin)?.let { it - nowRailMinute },
            )
        }

        val anchor = stops.firstOrNull { it.isAnchor } ?: stops.lastOrNull { it.state == State.PASSED }
        val next = stops.firstOrNull { it.state == State.NEXT }
            ?: stops.firstOrNull { (it.minutesFromNow ?: -1) > 0 }
        val totalKm = alightKm(route, alightSeq) - boardKm
        val hasDistances = totalKm > 0 && stops.any { it.kmFromOrigin > 0 }
        // Distance travelled within the passenger's own segment. The engine's `kmAlong` is measured
        // from the train's first station and is only trustworthy when a live (or schedule-interpolated)
        // position put it there; otherwise interpolate the anchor->next leg by the same fraction the
        // chart uses, so the bar, the sentence and the plot agree.
        val coveredKm = when {
            anchor == null -> 0
            pos.kmAlong > 0f -> pos.kmAlong.toInt()
            else -> anchor.kmFromOrigin + ((next?.kmFromOrigin ?: anchor.kmFromOrigin) - anchor.kmFromOrigin)
                .times(pos.fraction.coerceIn(0f, 1f)).toInt()
        }
        val covered = (coveredKm - boardKm).coerceAtLeast(0)
        val progressLabel: String
        val progressPct: Int
        if (hasDistances) {
            progressPct = if (totalKm > 0) ((covered * 100) / totalKm).coerceIn(0, 100) else 0
            progressLabel = "$covered of $totalKm km" +
                (if (pos.fraction > 0f && pos.kmAlong > 0f) " · ${(totalKm - covered).coerceAtLeast(0)} km left" else "")
        } else {
            // Without credible distances the honest denominator is stops travelled, and it must say so.
            val done = passedCount(stops)
            val span = (route.stops.indexOfLast { it.seq == alightSeq } -
                (route.stops.indexOfFirst { it.seq == boardSeq }).coerceAtLeast(0)).coerceAtLeast(1)
            progressPct = ((done * 100) / span).coerceIn(0, 100)
            progressLabel = "$done of $span stops covered · distances unavailable offline"
        }

        // The tick's denominator must be the *passenger's* segment, like the fill's, or the two marks
        // measure different journeys and the gap between them is meaningless.
        val seg = stops.filter { it.seq >= boardSeq && it.seq <= alightSeq }
        val schedAnchor = seg.lastOrNull { (it.schedDep ?: it.schedArr ?: Int.MAX_VALUE) <= nowRailMinute }
        val schedulePct = if (hasDistances && totalKm > 0) {
            ((schedAnchor?.kmFromBoard ?: 0) * 100 / totalKm).coerceIn(0, 100)
        } else {
            -1
        }

        return View(
            trainNo = route.trainNo,
            trainName = route.trainName,
            routeLabel = "${route.srcCode} → ${route.dstCode} · ${route.srcName} to ${route.dstName}",
            runDateLabel = "run of ${RailTime.formatDate(runEpochDay)}",
            headline = headline(route, pos, anchor, next, live, nowRailMinute),
            tone = tone(pos, live),
            provenanceLabel = pos.provenance.label,
            isLive = live,
            ageLabel = age(pos.ageSeconds),
            delayLabel = delayLabel(pos, live),
            nextLabel = nextLabel(next, route, alightSeq),
            progressLabel = progressLabel,
            progressPct = progressPct,
            schedulePct = schedulePct,
            rows = stops,
            hasDistances = hasDistances,
            note = note,
            anchorSeq = pos.anchorSeq,
        )
    }

    // --------------------------------------------------------------------------- row states

    /**
     * The one rule for "where is this stop relative to the train".
     *
     * With a live report, position is a fact about the anchor and everything is compared to its
     * sequence. Without one, position is a *consequence of the clock*, so a stop counts as passed only
     * once its scheduled departure is behind us — which is why the offline header below never says
     * "departed", it says what the timetable implies.
     */
    private fun state(
        s: StopRow, index: Int, nextIdx: Int, pos: TrainPosition, live: Boolean, now: Int,
    ): State {
        if (s.status == StopStatus.CANCELLED_HERE) return State.CANCELLED
        if (s.status == StopStatus.SKIPPED) return State.SKIPPED
        val anchorSeq = pos.anchorSeq
        if (live && anchorSeq > 0) {
            return when {
                s.seq < anchorSeq -> State.PASSED
                s.seq == anchorSeq ->
                    if (pos.status == StopStatus.ARRIVED || pos.status == StopStatus.UNKNOWN) State.HERE
                    else State.PASSED
                s.seq == pos.nextSeq -> State.NEXT
                else -> State.LATER
            }
        }
        // Schedule-only: judge each stop by its own clock, and let the anchor fall out of that.
        val pivot = s.departureMin ?: s.arrivalMin ?: return State.LATER
        val left = pivot <= now
        val isAnchor = anchorSeq > 0 && s.seq == anchorSeq
        return when {
            isAnchor && pos.status == StopStatus.ARRIVED -> State.HERE
            left -> State.PASSED
            index == nextIdx -> State.NEXT
            else -> State.LATER
        }
    }

    private fun headline(
        route: TrainRoute, pos: TrainPosition, anchor: Stop?, next: Stop?,
        live: Boolean, now: Int,
    ): String {
        if (anchor == null) {
            return if (pos.status == StopStatus.UPCOMING) "Not departed yet" else "Position not available yet"
        }
        val name = anchor.name
        val late = pos.delayMinutes?.let { if (abs(it) >= 2) " · ${RailTime.formatDelay(it)}" else "" } ?: ""
        if (anchor.state == State.CANCELLED) return "Cancelled at $name"
        if (!live) {
            // Everything here is an inference from the timetable. Say "should be", never "is".
            val gap = now - (anchor.schedDep ?: anchor.schedArr ?: now)
            val ahead = next?.let { it.kmFromBoard - anchor.kmFromBoard }
            return when {
                anchor.state == State.HERE -> "Should be at $name now"
                gap > 90 * 60 -> "Should have finished the run at ${route.dstName}"
                ahead != null && gap > 0 -> "Should be past $name, ${RailTime.formatDuration(gap)} into the " +
                    "$ahead km leg"
                else -> "Should have left $name by now"
            }
        }
        val platform = anchor.platform?.takeIf { it.isNotBlank() }?.let { " · platform $it" } ?: ""
        return when {
            anchor.state == State.HERE -> "At $name$platform"
            pos.fraction >= 0.95f && next != null -> "Approaching ${next.name}"
            pos.fraction > 0f && next != null -> "Between $name and ${next.name}"
            pos.status == StopStatus.DEPARTED -> "Departed $name$late"
            else -> "Last report at $name"
        }
    }

    private fun tone(pos: TrainPosition, live: Boolean): Tone = when {
        pos.status == StopStatus.CANCELLED_HERE || pos.status == StopStatus.SKIPPED -> Tone.PROBLEM
        !live -> Tone.SCHEDULE
        pos.delayMinutes == null -> Tone.NONE
        pos.delayMinutes!! >= 2 -> Tone.LIVE_LATE
        pos.delayMinutes!! <= -2 -> Tone.LIVE_EARLY
        else -> Tone.LIVE_OK
    }

    private fun delayLabel(pos: TrainPosition, live: Boolean): String {
        val d = pos.delayMinutes
        return when {
            !live && d == null -> "no live feed · times are the schedule"
            !live -> "assumed on time (no live feed)"
            d == null -> "delay not reported"
            abs(d) < 2 -> "on time"
            else -> RailTime.formatDelay(d)
        }
    }

    private fun nextLabel(next: Stop?, route: TrainRoute, alightSeq: Int): String {
        if (next == null) return "Last halt reached · destination ${route.dstName}"
        val t = next.showDep ?: next.showArr ?: return "Next: ${next.name}"
        val clock = RailTime.formatRailMinute(t)
        val when0 = next.minutesFromNow?.let { " · ${formatGap(it)}" } ?: ""
        val kind = if (next.estimated) "expected" else "due"
        val at = if (next.seq == alightSeq) "${next.name} (your stop)" else next.name
        return "Next: $at · $clock $kind$when0"
    }

    private fun age(seconds: Long): String = when {
        seconds <= 0 -> "live"
        seconds < 60 -> "updated ${seconds}s ago"
        seconds < 3600 -> "updated ${seconds / 60} min ago"
        else -> "updated ${seconds / 3600} h ago"
    }

    /**
     * "in 40 min" / "42 min ago" / "in 1h 30m" — the same words on both sides of now, so a column of
     * them can be scanned. Minutes below the hour are spelled out rather than abbreviated to `40m`,
     * because this string is read at arm's length across a platform; `formatDuration` keeps the compact
     * form for the dense meta lines it was written for.
     */
    fun formatGap(minutes: Int): String {
        if (minutes == 0) return "right now"
        val abs = if (minutes > 0) minutes else -minutes
        val body = if (abs < 60) "$abs min" else RailTime.formatDuration(abs)
        return if (minutes > 0) "in $body" else "$body ago"
    }

    private fun alightKm(route: TrainRoute, alightSeq: Int): Int =
        route.stops.firstOrNull { it.seq == alightSeq }?.schedKm
            ?: route.stops.lastOrNull()?.schedKm ?: 0

}
