package `in`.opentrack.india.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt
import `in`.opentrack.india.R
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.RouteBetween
import `in`.opentrack.india.domain.Suggestion
import `in`.opentrack.india.domain.TitleCase
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StationHit
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.TrainHit
import `in`.opentrack.india.model.TrainSummary

/**
 * Search results. Two rows of information per hit and nothing else — the decision a traveller makes
 * here is "does this train work for me", which needs train identity, times, duration and whether it
 * actually runs on the date they picked.
 *
 * The right-hand tag is the one thing a printed timetable cannot tell you: how long until it goes. It
 * is computed from the same rail-minute arithmetic the search used, so it cannot disagree with the times
 * beside it.
 */
class TrainHitAdapter(private val onClick: (TrainHit) -> Unit) :
    ListAdapter<TrainHit, TrainHitAdapter.Holder>(DIFF) {

    /** Minute-of-IST-epoch for "now", carried by the ViewModel. UI never reads the wall clock itself. */
    var nowMinute: Long = 0L

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val dep: TextView = view.findViewById(R.id.hitDep)
        val arr: TextView = view.findViewById(R.id.hitArr)
        val no: TextView = view.findViewById(R.id.hitNo)
        val name: TextView = view.findViewById(R.id.hitName)
        val meta: TextView = view.findViewById(R.id.hitMeta)
        val days: TextView = view.findViewById(R.id.hitDays)
        val tag: TextView = view.findViewById(R.id.hitTag)
        val go: TextView = view.findViewById(R.id.hitGo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_train_hit, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val hit = getItem(position)
        val ctx = holder.itemView.context
        holder.dep.text = RailTime.formatRailMinuteBare(hit.boardMinute)
        holder.arr.text = RailTime.formatRailMinute(hit.alightMinute)
        holder.no.text = buildString {
            append(hit.trainNo)
            hit.trainType?.let { append(" · ").append(RowText.type(it)) }
        }
        holder.name.text = RowText.of(hit.trainName, keep = setOf(hit.fromCode, hit.toCode))
        holder.meta.text = buildString {
            append(hit.fromCode).append(" → ").append(hit.toCode)
            if (hit.distanceKm > 0) append(" · ").append(hit.distanceKm).append(" km")
            append(" · ").append(RailTime.formatDuration(hit.durationMin))
            if (RailTime.dayOffsetOf(hit.alightMinute) > RailTime.dayOffsetOf(hit.boardMinute)) {
                append(" · arrives next day")
            }
            // The provenance suffix rides the meta line: a row must say whether its time is the
            // timetable's or a live source's (v2 slice 1 - "offline never looks live").
            append(" · ").append(hit.sourceTag ?: "timetable")
        }
        holder.days.text = TrainResultsDisplay.frequency(hit.runDays)
        holder.days.contentDescription = if (hit.runDays == null) "Operating days unavailable"
            else "Runs " + RowText.dayNames(hit.runDays) + "; Sunday through Saturday, underlined days operate"
        val delta = hit.boardEpochMinute - nowMinute
        holder.tag.text = TrackModel.formatGap(delta.toInt())
        holder.tag.setBackgroundResource(
            when {
                delta in -20L..0L -> R.drawable.bg_badge_problem
                delta > 0 && delta <= 180 -> R.drawable.bg_pill_accent
                else -> R.drawable.bg_pill
            }
        )
        holder.tag.setTextColor(ctx.getColor(
            when {
                delta in -20L..0L -> R.color.late
                delta in 1L..180L -> R.color.accent
                else -> R.color.text2
            }
        ))
        holder.itemView.setOnClickListener { onClick(hit) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TrainHit>() {
            override fun areItemsTheSame(a: TrainHit, b: TrainHit) =
                a.trainNo == b.trainNo && a.originDateEpochDay == b.originDateEpochDay &&
                    a.fromCode == b.fromCode && a.toCode == b.toCode

            override fun areContentsTheSame(a: TrainHit, b: TrainHit) = a == b
        }
    }
}

/**
 * The route table. Every string on a row comes from [TrackModel.Stop]; the adapter adds no facts, which
 * is what keeps the sentence at the top of the screen and the row it describes in agreement.
 *
 * Per row: scheduled arrival/departure in the dim column, live-or-expected in the bright one, and a tag
 * naming which of the two the bright column is. A projected minute and a confirmed minute have to be
 * distinguishable at a glance — that distinction is the whole reason a passenger opens this app instead
 * of a timetable PDF.
 */
class StopAdapter : ListAdapter<TrackModel.Stop, StopAdapter.Holder>(DIFF) {
    /** Activity supplies the explanation/retry; this adapter never invents map dots. */
    var onMapUnavailable: ((String) -> Unit)? = null
    var mapSource: String? = null
    var mappedLegStarts: Set<String> = emptySet()

    private var expandedCode: String? = null
    private var routePoints: Map<String, List<StopRow>> = emptyMap()
    private var projectedLeg: RouteBetween.LegProjection? = null
    /** Only the selected website's parsed past cards; never an ACTUAL/marker source. */
    var websiteClaims: Map<String, LiveStopFields> = emptyMap()
        set(value) {
            if (field != value) { field = value; notifyDataSetChanged() }
        }
    var websiteIntermediateReports: Map<String, LiveStopFields> = emptyMap()
        set(value) {
            if (field != value) { field = value; notifyDataSetChanged() }
        }
    var sourceNameForMarker: String = "NTES"
        set(value) {
            if (field != value) { field = value; if (projectedLeg != null) notifyDataSetChanged() }
        }
    var legWebsiteEventMinute: Int? = null
        set(value) {
            if (field != value) { field = value; if (projectedLeg != null) notifyDataSetChanged() }
        }
    var legAgeSeconds: Long? = null
        set(value) {
            if (field?.div(60) != value?.div(60)) {
                field = value
                if (projectedLeg != null || haltMarker != null) notifyDataSetChanged()
            } else field = value
        }

    fun setProjectedLeg(leg: RouteBetween.LegProjection?) {
        if (projectedLeg == leg) return
        projectedLeg = leg
        notifyDataSetChanged()
    }

    /** A group is attached to the preceding scheduled halt. No point becomes a timed halt. */
    fun setRoutePoints(groups: Map<String, List<StopRow>>) {
        if (routePoints == groups) return
        routePoints = groups
        if (expandedCode?.let { code -> code !in groups &&
                projectedLeg?.takeIf { it.fromCode == code }?.points.isNullOrEmpty() } == true)
            expandedCode = null
        notifyDataSetChanged()
    }

    /** The halt the mirror's actuals say the train is at or making for - tinted, never silent. */
    var haltMarker: TableMarker.Halt? = null
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    var highlightCode: String? = null
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged() // the timetable rows themselves may be unchanged
            }
        }

    /** Per-stop source annotations, including manual uncorroborated candidates. A source
     * selection can leave the row objects unchanged, so explicitly rebind visible rows. */
    var reports: Map<String, String> = emptyMap()
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val row: View = view.findViewById(R.id.stopRow)
        val inlineLegGroup: LinearLayout = view.findViewById(R.id.inlineLegGroup)
        val inlineLeg: InlineLegView = view.findViewById(R.id.inlineLeg)
        val inlineLegCaption: TextView = view.findViewById(R.id.inlineLegCaption)
        val inlineLegAge: TextView = view.findViewById(R.id.inlineLegAge)
        val routeToggle: TextView = view.findViewById(R.id.routeToggle)
        val details: LinearLayout = view.findViewById(R.id.stopDetails)
        val primaryTime: TextView = view.findViewById(R.id.stopPrimaryTime)
        val timeType: TextView = view.findViewById(R.id.stopTimeType)
        val stopToggle: TextView = view.findViewById(R.id.stopToggle)
        val dot: View = view.findViewById(R.id.stopDot)
        val trainMarker: StopRailMarkerView = view.findViewById(R.id.stopTrainMarker)
        val marker: TextView = view.findViewById(R.id.haltMarker)
        val markerAge: TextView = view.findViewById(R.id.stopMarkerAge)
        val name: TextView = view.findViewById(R.id.stopName)
        val meta: TextView = view.findViewById(R.id.stopMeta)
        val status: TextView = view.findViewById(R.id.stopStatus)
        val report: TextView = view.findViewById(R.id.stopReport)
        val schedArr: TextView = view.findViewById(R.id.schedArr)
        val schedDep: TextView = view.findViewById(R.id.schedDep)
        val liveArr: TextView = view.findViewById(R.id.liveArr)
        val liveDep: TextView = view.findViewById(R.id.liveDep)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_stop, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val s = getItem(position)
        val ctx = holder.itemView.context
        holder.name.text = RowText.of(s.name)
        holder.name.setTextColor(ctx.getColor(colorFor(s)))
        holder.meta.text = buildString {
            append(s.code)
            if (s.showKm) append(" · ").append(s.kmFromOrigin).append(" km")
            if (s.isBoarding) append(" · ").append(ctx.getString(R.string.tag_board))
            else if (s.isAlighting) append(" · ").append(ctx.getString(R.string.tag_alight))
            s.platform?.takeIf { it.isNotBlank() }?.let {
                append(if (s.source == TrackModel.MinuteSource.SITE_REPORTED) " · site pf "
                    else " · feed pf ").append(it)
            }
        }
        val marker = haltMarker?.takeIf { it.code.equals(s.code, true) }
        // The train icon and age already identify the last report. Keep its
        // evidence available to accessibility without a redundant banner.
        holder.marker.visibility = View.GONE
        holder.marker.text = ""
        holder.marker.contentDescription = marker?.caption ?: ""
        val ageLabel = marker?.takeUnless { it.caption.startsWith("RailYatri station claim") }
            ?.let { legAgeSeconds?.let(LegGeometry::ageLabel) }
        holder.markerAge.text = legAgeSeconds?.takeIf { ageLabel != null }
            ?.let(LegGeometry::compactAgeLabel) ?: ""
        holder.markerAge.visibility = if (ageLabel == null) View.GONE else View.VISIBLE
        holder.markerAge.setTextColor(ctx.getColor(if (legAgeSeconds?.let {
                it >= SourceFreshness.STALE_AFTER_MS / 1_000L } == true) R.color.late else R.color.live))
        holder.markerAge.contentDescription = ageLabel?.let { "Last reported event $it" } ?: ""
        holder.marker.setTextColor(ctx.getColor(R.color.live))
        val verified = s.source == TrackModel.MinuteSource.REPORTED
        val website = s.source == TrackModel.MinuteSource.SITE_REPORTED
        val stationClaim = reports[s.code]?.startsWith("RailYatri root claims") == true
        holder.status.text = when {
            website && s.showDep != null && s.showArr == null ->
                "Website arrival not validated · departure shown"
            website && s.showDep != null -> "Departed · later position unknown"
            website -> "Arrived · departure unknown"
            stationClaim -> "At station claimed · time unknown"
            verified && s.isAnchor && s.showDep != null -> "Departed station · later position unknown"
            verified && s.showDep != null -> "Departed · reported"
            verified && s.isAnchor && s.state == TrackModel.State.PASSED -> "Departed · time unknown"
            verified && s.showArr != null -> "Arrived at station · departure unknown"
            s.state == TrackModel.State.PASSED -> "Past on schedule · actual unknown"
            s.state == TrackModel.State.HERE -> "At stop on schedule · unconfirmed"
            s.state == TrackModel.State.NEXT && s.source == TrackModel.MinuteSource.PROJECTED ->
                "Next · estimated, not confirmed"
            s.state == TrackModel.State.NEXT -> "Next · schedule only"
            s.source == TrackModel.MinuteSource.PROJECTED -> "Estimated · not reported"
            else -> "Scheduled"
        }
        // Reported clocks have provenance in their content descriptions. Do not
        // repeat unhelpful prose below every halt; preserve exceptional warnings.
        holder.status.visibility = if (s.state == TrackModel.State.CANCELLED || s.state == TrackModel.State.SKIPPED || stationClaim)
            View.VISIBLE else View.GONE
        holder.status.setTextColor(ctx.getColor(when {
            stationClaim || website || verified -> R.color.live
            s.source == TrackModel.MinuteSource.PROJECTED -> R.color.warn
            else -> R.color.text3
        }))
        // A successful undated website response is useful to compare at the stop,
        // but cannot populate ACTUAL, the train glyph, delay, or a run-verified event.
        val webClaim = WebsiteClaimPresentation.stationClaim(websiteClaims[s.code])
        holder.report.text = webClaim ?: reports[s.code].orEmpty()
        holder.report.visibility = if (webClaim == null) View.GONE else View.VISIBLE
        holder.report.contentDescription = webClaim?.let {
            "Unverified whereismytrain.org.in website past-card claim. $it. " +
                "Origin run date is inferred, not printed by the website. Not an actual train observation."
        } ?: ""
        // Two concise pairs are always present: dim timetable at left, selected
        // source at right. Arrows mean arrival / departure; missing reports stay —.
        fun time(minute: Int?): String = minute?.let(RailTime::formatRailMinute) ?: "—"
        holder.schedArr.text = time(s.schedArr)
        holder.schedDep.text = time(s.schedDep)
        DirectionArrowDrawable.attach(holder.schedArr, arriving = true, reported = false)
        DirectionArrowDrawable.attach(holder.schedDep, arriving = false, reported = false)
        val reported = s.source != TrackModel.MinuteSource.SCHEDULE
        val late: Boolean = (s.delay ?: 0) >= 3
        holder.liveArr.text = time(s.showArr.takeIf { reported })
        holder.liveDep.text = time(s.showDep.takeIf { reported })
        DirectionArrowDrawable.attach(holder.liveArr, arriving = true, reported = reported)
        DirectionArrowDrawable.attach(holder.liveDep, arriving = false, reported = reported)
        val evidence = when (s.source) {
            TrackModel.MinuteSource.SITE_REPORTED -> "Website claim"
            TrackModel.MinuteSource.PROJECTED -> "Estimate"
            TrackModel.MinuteSource.REPORTED -> "Reported"
            else -> "Not reported"
        }
        holder.schedArr.contentDescription = "Scheduled arrival ${time(s.schedArr)}"
        holder.schedDep.contentDescription = "Scheduled departure ${time(s.schedDep)}"
        holder.liveArr.contentDescription = "$evidence arrival ${time(s.showArr.takeIf { reported })}"
        holder.liveDep.contentDescription = "$evidence departure ${time(s.showDep.takeIf { reported })}"
        // Late = red (the colour a traveller scans for); on-time actual = green; estimate = amber.
        // A pure-timetable row stays grey in both - no number pretends to be news.
        val color = when {
            !reported -> R.color.text2
            s.source == TrackModel.MinuteSource.PROJECTED ||
                s.source == TrackModel.MinuteSource.SITE_REPORTED -> R.color.warn
            else -> R.color.live
        }
        holder.liveArr.setTextColor(ctx.getColor(color))
        holder.liveDep.setTextColor(ctx.getColor(color))
        val hasShownTime = reported && (s.showArr != null || s.showDep != null)
        val primary = if (hasShownTime) s.showDep ?: s.showArr else s.schedArr ?: s.schedDep
        holder.primaryTime.text = primary?.let(RailTime::formatRailMinute) ?: "—"
        holder.timeType.text = if (hasShownTime) evidence else "SCHEDULE"
        holder.details.visibility = View.GONE
        val points = routePoints[s.code].orEmpty()
        val hasPoints = points.isNotEmpty() || projectedLeg?.takeIf {
            it.fromCode.equals(s.code, true) }?.points?.isNotEmpty() == true
        // Tapping the station reveals dots. A second button under the report clocks
        // displaced their pair above the scheduled clocks on large-font phones.
        holder.stopToggle.visibility = View.GONE
        holder.stopToggle.text = if (expandedCode == s.code) "Hide ▴" else "Stations ▾"
        holder.dot.setBackgroundResource(dotFor(s))
        holder.dot.visibility = if (marker == null) View.VISIBLE else View.GONE
        holder.trainMarker.visibility = if (marker == null) View.GONE else View.VISIBLE
        holder.trainMarker.ageSeconds = if (marker?.code.equals(s.code, true) &&
            marker?.caption?.startsWith("RailYatri station claim") != true) legAgeSeconds else null
        holder.trainMarker.contentDescription = marker?.caption ?: ""
        // Keep the next stop directly after the compact leg. The former 34dp
        // projected-row spacer looked like missing content on a phone.
        val nextKm = currentList.getOrNull(position + 1)?.kmFromOrigin
        val betweenKm = nextKm?.takeIf { it >= 0 && s.kmFromOrigin >= 0 &&
            it >= s.kmFromOrigin }?.minus(s.kmFromOrigin)
        val screenDp = ctx.resources.displayMetrics.heightPixels / ctx.resources.displayMetrics.density
        val hasTrainLeg = projectedLeg?.fromCode.equals(s.code, true)
        val gapPx = betweenKm?.let {
            (LegGeometry.stopGapDp(it, screenDp) * ctx.resources.displayMetrics.density).toInt()
        } ?: 0
        holder.row.setPadding(holder.row.paddingLeft,
            ctx.resources.getDimensionPixelSize(R.dimen.gap_xs),
            holder.row.paddingRight,
            ctx.resources.getDimensionPixelSize(R.dimen.gap_xs) + if (hasTrainLeg || hasPoints || nextKm == null) 0 else gapPx)
        // The stop dot and its label already mark the reported halt. Full-width
        // row bands made this look like a data table, not a continuous route.
        holder.row.setBackgroundColor(0x00000000)
        val nextStop = currentList.getOrNull(position + 1)
        val leg = projectedLeg?.takeIf { it.fromCode.equals(s.code, true) }
        val disclosed = expandedCode == s.code && hasPoints
        val available = if (points.isEmpty()) leg?.points.orEmpty() else points.mapIndexed { index, point ->
            val matching = leg?.points?.firstOrNull { it.code.equals(point.code, true) }
            val kmFraction = nextStop?.let { next ->
                val length = next.kmFromOrigin - s.kmFromOrigin
                if (point.schedKm > s.kmFromOrigin && point.schedKm < next.kmFromOrigin && length > 0)
                    (point.schedKm - s.kmFromOrigin).toFloat() / length else null
            }
            val fraction = matching?.fraction ?: kmFraction ?: (index + 1f) / (points.size + 1f)
            // Do not give the historical 16227 corridor invented passing clocks.
            // Other accepted maps retain their existing explicitly ~ approximate
            // schedule interpolation; they never become actual or live times.
            val planned = point.arrivalMin ?: if (mapSource?.startsWith("Offline historical") == true)
                null else nextStop?.let { next ->
                val from = s.schedDep ?: s.schedArr
                val to = next.schedArr ?: next.schedDep
                if (kmFraction != null && from != null && to != null && to >= from)
                    (from + (to - from) * kmFraction).roundToInt() else null
            }
            val web = websiteIntermediateReports[point.code]
            // An arrival-only website past card remains a labelled claim at the
            // mapped intermediate; it never turns the point into a reported halt.
            val stopped = point.isHalt || (web?.actualArrivalMin != null &&
                web.actualDepartureMin != null && web.actualDepartureMin > web.actualArrivalMin)
            RouteBetween.LegPoint(point.code, point.name, point.schedKm, fraction,
                planned, point.arrivalMin == null && planned != null, stopped,
                web?.actualArrivalMin, web?.actualDepartureMin)
        }
        // A mapped leg has a visible, full-width rail even while its station names
        // are folded. Previously only the stop row was tappable until expansion.
        val displayedLeg = leg?.copy(points = available) ?: if (hasPoints && nextStop != null)
            RouteBetween.LegProjection(s.name, nextStop.name, 0f, null, s.kmFromOrigin,
                s.code, nextStop.code, available) else null
        val mapUnavailable = nextStop != null && available.isEmpty() &&
            s.code.uppercase() !in mappedLegStarts
        holder.inlineLegGroup.visibility = if (displayedLeg != null) View.VISIBLE else View.GONE
        if (displayedLeg == null) {
            // Recycled children must not retain the previous leg's disclosure.
            holder.inlineLegCaption.visibility = View.GONE
            holder.routeToggle.visibility = View.GONE
            holder.inlineLegAge.visibility = View.GONE
        }
        holder.inlineLeg.projection = displayedLeg
        holder.inlineLeg.historicalMap = mapSource?.startsWith("Offline historical") == true
        holder.inlineLeg.compactStatic = leg == null
        holder.inlineLeg.showLabels = disclosed
        // A pinned halt uses the locomotive IN its station row. Do not draw a
        // second locomotive 24dp down in the next leg (the apparent third dot).
        holder.inlineLeg.showTrain = leg != null &&
            !(leg.fraction == 0f && marker?.code.equals(s.code, true))
        holder.inlineLeg.distanceKm = nextStop?.let { (it.kmFromOrigin - s.kmFromOrigin).coerceAtLeast(0) } ?: 0
        holder.inlineLeg.ageSeconds = if (leg != null &&
            leg.markerKind != RouteBetween.MarkerKind.PROJECTED) legAgeSeconds else null
        holder.inlineLeg.siteEventMinute = if (leg?.markerKind ==
            RouteBetween.MarkerKind.SITE_INTERMEDIATE) legWebsiteEventMinute else null
        if (displayedLeg != null) {
            val web = leg?.markerKind == RouteBetween.MarkerKind.WEB_CACHE_CANDIDATE
            val site = leg?.markerKind == RouteBetween.MarkerKind.SITE_INTERMEDIATE
            val siteHalt = leg?.markerKind == RouteBetween.MarkerKind.SITE_LAST_STOP
            val reportedHalt = leg?.markerKind == RouteBetween.MarkerKind.REPORTED_HALT
            val stationClaim = leg?.markerKind == RouteBetween.MarkerKind.STATION_CLAIM
            // A drawn marker, source selector and station clocks carry the facts;
            // this duplicate "last stop / motion unknown" caption hid the route.
            val caption = ""
            holder.inlineLegCaption.text = caption
            holder.inlineLegCaption.visibility = if (caption.isEmpty()) View.GONE else View.VISIBLE
            holder.inlineLegCaption.setTextColor(ctx.getColor(R.color.text2))
            holder.inlineLegAge.text = holder.inlineLeg.ageSeconds?.let { seconds ->
                if (seconds < 60) "Just now" else TableMarker.elapsedLabel(seconds * 1_000L) + " ago"
            }.orEmpty()
            // The age belongs beside the locomotive, not on a separate line near stop names.
            holder.inlineLegAge.visibility = View.GONE
            holder.inlineLegAge.contentDescription = if (holder.inlineLegAge.text.isEmpty()) ""
                else "Last reported event ${holder.inlineLegAge.text}"
            holder.routeToggle.text = when {
                disclosed -> "Hide ▴"
                else -> "Stations ▾"
            }
            holder.routeToggle.contentDescription = when {
                disclosed -> "Hide intermediate stations"
                else -> "Show ${available.size} intermediate stations"
            }
            holder.routeToggle.visibility = if (available.isNotEmpty()) View.VISIBLE else View.GONE
            holder.routeToggle.setOnClickListener {
                toggleStations(s.code, position, holder)
            }
            val pointDescription = available.joinToString("; ") { point ->
                val clock = point.passageMinute?.let {
                    (if (point.approximate) "approximately " else "scheduled ") +
                        RailTime.formatRailMinute(it)
                } ?: "passage time unavailable"
                val actual = point.siteArrivalMin?.let { ", website arrival claim " +
                    RailTime.formatRailMinute(it) } ?: ", no validated stop arrival"
                "${RowText.of(point.name)} (${point.code}), $clock$actual" +
                    (if (point.mapKm >= 0) ", ${point.mapKm} km" else ", distance unavailable")
            }
            holder.inlineLeg.contentDescription = if (leg == null)
                "Intermediate stations on ${mapSource ?: "the static route"}; no train-position claim. " +
                    pointDescription
            else caption +
                (holder.inlineLeg.ageSeconds?.let { " · reported event ${LegGeometry.ageLabel(it)}" }
                    ?: " · event time unknown") +
                " · not GPS · ${displayedLeg.from} to ${displayedLeg.to} " +
                "${(displayedLeg.fraction * 100).toInt()} percent of scheduled leg by " +
                (if (stationClaim) "unverified station claim (no passage time or measured distance)"
                    else if (reportedHalt || siteHalt) "last reported halt (no measured distance since)"
                    else if (site) "static map of the site-reported station"
                    else if (web) "web-cache km claim" else "estimated distance") +
                (if (site && holder.inlineLeg.siteEventMinute != null)
                    " · website-published event " +
                        RailTime.formatRailMinute(holder.inlineLeg.siteEventMinute!!) else "") +
                (if (pointDescription.isEmpty()) "" else " · intermediate stations from " +
                    (mapSource ?: "static map") + ": $pointDescription")
        }
        fun onLegTap() {
            `in`.opentrack.india.core.Diary.get()?.log("ROUTE_UI") {
                "tap leg ${s.code}->${nextStop?.code ?: "end"} " +
                    "points=${available.size} expanded=$disclosed map=${if (mapUnavailable) "unavailable" else "available"}"
            }
            if (hasPoints) toggleStations(s.code, position, holder)
            else if (mapUnavailable) onMapUnavailable?.invoke(s.code)

        }
        holder.row.setOnClickListener { onLegTap() }
        holder.row.isClickable = hasPoints || mapUnavailable
        // The line, dots, blank leg and caption are all one tap target, not only
        // the station name or tiny Hide button. A second tap folds the same leg.
        holder.inlineLegGroup.isClickable = hasPoints || mapUnavailable
        holder.inlineLegGroup.setOnClickListener { onLegTap() }
        holder.inlineLeg.isClickable = hasPoints || mapUnavailable
        holder.inlineLeg.setOnClickListener { onLegTap() }
        holder.row.contentDescription = "${s.name}; " +
            "${holder.schedArr.contentDescription}; ${holder.schedDep.contentDescription}; " +
            "${holder.liveArr.contentDescription}; ${holder.liveDep.contentDescription}" +
            when {
                hasPoints -> "; tap to ${if (disclosed) "hide" else "show"} " +
                    "${available.size} intermediate stations"
                mapUnavailable -> "; intermediate map unavailable; tap for details"
                else -> ""
            }
    }

    private fun toggleStations(code: String, fallbackPosition: Int, holder: Holder) {
        val previous = expandedCode
        expandedCode = if (previous == code) null else code
        // Show the result on this very tap, even if RecyclerView is deferring a
        // change animation/rebind while the user scrolls the long train route.
        val opened = expandedCode == code
        holder.inlineLeg.showLabels = opened
        holder.routeToggle.text = if (opened) "Hide ▴" else "Stations ▾"
        holder.routeToggle.contentDescription = if (opened)
            "Hide intermediate stations" else "Show intermediate stations"
        holder.inlineLeg.requestLayout()
        holder.inlineLeg.invalidate()
        `in`.opentrack.india.core.Diary.get()?.log("ROUTE_UI") {
            "leg $code ${if (opened) "opened" else "closed"}; inline points=" +
                (holder.inlineLeg.projection?.points?.size ?: 0)
        }
        if (previous != null && previous != code) currentList.indexOfFirst { it.code == previous }
            .takeIf { it >= 0 }?.let(::notifyItemChanged)
        notifyItemChanged(holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
            ?: fallbackPosition)
    }

    /**
     * One short word under the live column, naming what that column holds.
     *
     * The rule the user asked for is exactly this: a stop the train has covered is scheduled *and*
     * actual, a stop still ahead is scheduled *and* expected. Colour separates them in the columns
     * themselves, which is the fast read; the word is the slow read, and it is what makes a screenshot
     * or a colour-blind passenger unambiguous. "no report" and "timetable" are spelled out rather than
     * left blank because a passed stop with no actual minute, and a screen with no feed at all, are
     * statements about the data — and a dash reads like the app gave up.
     */
    private fun tagFor(s: TrackModel.Stop, ctx: android.content.Context): String = when {
        s.state == TrackModel.State.CANCELLED -> ctx.getString(R.string.tag_cancelled)
        s.state == TrackModel.State.SKIPPED -> ctx.getString(R.string.tag_skipped)
        s.state == TrackModel.State.HERE -> ctx.getString(R.string.tag_here)
        // The one row where counting down beats labelling, and the header repeats it anyway.
        s.state == TrackModel.State.NEXT ->
            s.minutesFromNow?.let { TrackModel.formatGap(it) } ?: ctx.getString(R.string.tag_due)
        s.showArr == null && s.showDep == null -> ctx.getString(R.string.tag_no_report)
        s.source == TrackModel.MinuteSource.PROJECTED -> ctx.getString(R.string.tag_expected)
        s.source == TrackModel.MinuteSource.SITE_REPORTED -> "Site report"
        s.source == TrackModel.MinuteSource.SCHEDULE -> ctx.getString(R.string.tag_timetable)
        else -> ctx.getString(R.string.tag_actual)
    }

    private fun tagColorFor(s: TrackModel.Stop): Int = when {
        s.state == TrackModel.State.HERE && s.source == TrackModel.MinuteSource.REPORTED &&
            s.showArr != null && s.showDep == null -> R.color.warn
        s.state == TrackModel.State.HERE && s.source == TrackModel.MinuteSource.REPORTED -> R.color.accent
        s.state == TrackModel.State.CANCELLED || s.state == TrackModel.State.SKIPPED -> R.color.late
        s.state == TrackModel.State.NEXT -> R.color.text1
        s.source == TrackModel.MinuteSource.PROJECTED ||
            s.source == TrackModel.MinuteSource.SITE_REPORTED -> R.color.warn
        s.source == TrackModel.MinuteSource.SCHEDULE -> R.color.text3
        s.showArr != null || s.showDep != null -> R.color.text2
        else -> R.color.text3
    }

    /** A source does not recolour the route. The row's Site/Act wording and the
     * hero convey confidence; identical reported stop states get identical dots. */
    private fun hasStopEvent(s: TrackModel.Stop): Boolean =
        s.source == TrackModel.MinuteSource.REPORTED ||
            s.source == TrackModel.MinuteSource.SITE_REPORTED

    internal fun dotFor(s: TrackModel.Stop): Int = when (s.state) {
        // An arrival without departure is an at-station report, not a passed
        // halt. Give it the ring used for HERE on both NTES and the website.
        TrackModel.State.HERE -> if (hasStopEvent(s))
            R.drawable.dot_here else R.drawable.dot_later
        TrackModel.State.PASSED -> if (hasStopEvent(s))
            R.drawable.dot_passed else R.drawable.dot_later
        TrackModel.State.CANCELLED, TrackModel.State.SKIPPED -> R.drawable.dot_problem
        else -> R.drawable.dot_later
    }

    private fun colorFor(s: TrackModel.Stop): Int = when {
        s.state == TrackModel.State.CANCELLED || s.state == TrackModel.State.SKIPPED -> R.color.late
        s.state == TrackModel.State.HERE && hasStopEvent(s) &&
            s.showArr != null && s.showDep == null -> R.color.warn
        s.state == TrackModel.State.HERE && hasStopEvent(s) -> R.color.accent
        s.source == TrackModel.MinuteSource.SCHEDULE -> R.color.text2
        s.state == TrackModel.State.LATER -> R.color.text2
        else -> R.color.text1
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TrackModel.Stop>() {
            override fun areItemsTheSame(a: TrackModel.Stop, b: TrackModel.Stop) = a.seq == b.seq
            override fun areContentsTheSame(a: TrackModel.Stop, b: TrackModel.Stop) = a == b
        }
    }
}

/**
 * Name casing and the small vocabularies the rows print.
 *
 * Named for what it does to a row rather than for the casing rule it delegates to: `TitleCase` is a
 * top-level object in `domain`, and a second type of that name in the same package as `Suggestion` once
 * made the pair unreadable (the checker flagged it the moment they coexisted).
 */
object RowText {
    fun of(raw: String, keep: Set<String> = emptySet()): String = TitleCase.of(raw, keep)

    fun rows(hits: List<StationHit>): List<Suggestion> = hits.map { Suggestion.ofStation(it) }
    fun trainRows(hits: List<TrainSummary>): List<Suggestion> = hits.map { Suggestion.ofTrain(it) }

    /** "SUPERFAST_EXPRESS" -> "Superfast Exp"; the abbreviations timetables use, kept short. */
    fun type(raw: String): String {
        val t = of(raw)
        return when {
            t.equals("Superfast Express", true) -> "SF Exp"
            t.equals("Mail/Express", true) -> "Exp"
            else -> if (t.length > 12) t.substringBefore(' ') else t
        }
    }

    fun dayNames(mask: Int): String {
        val names = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val bits = ArrayList<String>(7)
        for (i in 0..6) if (mask and (1 shl (6 - i)) != 0) bits += names[i]
        return when (bits.size) {
            7 -> "daily"
            0 -> "not scheduled"
            else -> bits.joinToString("/")
        }
    }
}
